package com.sentinelpay.ledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.exception.AccountNotActiveException;
import com.sentinelpay.common.exception.AccountNotFoundException;
import com.sentinelpay.common.exception.ApiException;
import com.sentinelpay.common.exception.CurrencyMismatchException;
import com.sentinelpay.common.exception.IdempotencyConflictException;
import com.sentinelpay.common.exception.InsufficientFundsException;
import com.sentinelpay.common.exception.InvalidAmountException;
import com.sentinelpay.common.metrics.LedgerMetrics;
import com.sentinelpay.ledger.domain.Account;
import com.sentinelpay.ledger.domain.AccountStatus;
import com.sentinelpay.ledger.domain.Direction;
import com.sentinelpay.ledger.domain.LedgerEntry;
import com.sentinelpay.ledger.domain.TransactionEntity;
import com.sentinelpay.ledger.domain.TransferRequest;
import com.sentinelpay.ledger.domain.TransferResult;
import com.sentinelpay.ledger.domain.TxStatus;
import com.sentinelpay.ledger.domain.TxType;
import com.sentinelpay.ledger.repository.AccountRepository;
import com.sentinelpay.ledger.repository.LedgerEntryRepository;
import com.sentinelpay.ledger.repository.TransactionRepository;
import com.sentinelpay.outbox.domain.OutboxEvent;
import com.sentinelpay.outbox.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerTransferService {

    private static final String POSTED_EVENT = "TransferPostedEvent";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final LedgerMetrics metrics;

    public LedgerTransferService(AccountRepository accountRepository,
                                 TransactionRepository transactionRepository,
                                 LedgerEntryRepository ledgerEntryRepository,
                                 OutboxRepository outboxRepository,
                                 ObjectMapper objectMapper,
                                 LedgerMetrics metrics) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult executeTransfer(TransferRequest request) {
        try {
            TransferResult result = metrics.recordTransferDuration(
                    () -> post(request, TxType.TRANSFER, POSTED_EVENT));
            long postedAt = System.nanoTime();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        metrics.recordCommitDuration(System.nanoTime() - postedAt);
                    }
                });
            }
            metrics.transfer(result.replay() ? "IDEMPOTENT_REPLAY" : result.status(), request.currency());
            return result;
        } catch (ApiException exception) {
            metrics.transfer(exception.code().name(), request.currency());
            throw exception;
        }
    }

    /**
     * Internal funding path used by account creation and the genesis seeder.
     * System accounts may go negative; user-to-user transfers may not.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult executeSystemDeposit(UUID sourceAccountId,
                                               UUID destinationAccountId,
                                               long amountMinor,
                                               String currency,
                                               String idempotencyKey,
                                               String description) {
        TransferRequest request = new TransferRequest(idempotencyKey, sourceAccountId,
                destinationAccountId, amountMinor, currency, description);
        return post(request, TxType.DEPOSIT, POSTED_EVENT);
    }

    private TransferResult post(TransferRequest request, TxType txType, String eventType) {
        UUID firstLockId = request.sourceAccountId().compareTo(request.destinationAccountId()) < 0
                ? request.sourceAccountId() : request.destinationAccountId();
        UUID secondLockId = firstLockId.equals(request.sourceAccountId())
                ? request.destinationAccountId() : request.sourceAccountId();

        Account first = metrics.recordLock(() -> accountRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new AccountNotFoundException(firstLockId)));
        Account second = metrics.recordLock(() -> accountRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new AccountNotFoundException(secondLockId)));

        Account source = firstLockId.equals(request.sourceAccountId()) ? first : second;
        Account destination = firstLockId.equals(request.destinationAccountId()) ? first : second;

        requireActive(source);
        requireActive(destination);

        if (!source.currency().equals(request.currency())
                || !destination.currency().equals(request.currency())) {
            throw new CurrencyMismatchException(source.currency(), destination.currency(),
                    request.currency());
        }

        if (!source.isSystem() && source.balanceMinor() < request.amountMinor()) {
            throw new InsufficientFundsException(source.accountId(), source.balanceMinor(),
                    request.amountMinor());
        }

        long newSourceBalance = subtract(source.balanceMinor(), request.amountMinor());
        long newDestinationBalance = add(destination.balanceMinor(), request.amountMinor());

        Instant now = Instant.now();
        UUID transactionId = UUID.randomUUID();
        TransactionEntity transaction = new TransactionEntity(
                transactionId,
                request.idempotencyKey(),
                txType,
                TxStatus.POSTED,
                request.currency(),
                request.description(),
                metadata(request),
                null,
                now);

        if (!transactionRepository.insertIfAbsent(transaction)) {
            TransactionEntity winner = transactionRepository
                    .findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IdempotencyConflictException(
                            request.idempotencyKey(), "Concurrent request won but is not readable"));
            return replay(winner, request);
        }

        updateBalance(first, firstLockId.equals(source.accountId()) ? newSourceBalance : newDestinationBalance);
        updateBalance(second, secondLockId.equals(source.accountId()) ? newSourceBalance : newDestinationBalance);

        ledgerEntryRepository.batchInsert(List.of(
                new LedgerEntry(UUID.randomUUID(), transactionId, source.accountId(), Direction.DEBIT,
                        -request.amountMinor(), newSourceBalance, now),
                new LedgerEntry(UUID.randomUUID(), transactionId, destination.accountId(), Direction.CREDIT,
                        request.amountMinor(), newDestinationBalance, now)));

        outboxRepository.insert(outboxEvent(transactionId, eventType, request, newSourceBalance,
                newDestinationBalance, now));

        return new TransferResult(transactionId, request.idempotencyKey(), TxStatus.POSTED.name(),
                request.amountMinor(), request.currency(), newSourceBalance, newDestinationBalance,
                now, false);
    }

    private void updateBalance(Account account, long newBalance) {
        int updated = accountRepository.updateBalance(account.accountId(), newBalance, account.version());
        if (updated != 1) {
            throw new IllegalStateException("Account " + account.accountId()
                    + " changed while its row lock was held");
        }
    }

    private void requireActive(Account account) {
        if (account.status() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.accountId(), account.status().name());
        }
    }

    private TransferResult replay(TransactionEntity existing, TransferRequest request) {
        verifySamePayload(existing, request);
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(existing.transactionId());
        LedgerEntry debit = entries.stream()
                .filter(entry -> entry.direction() == Direction.DEBIT)
                .findFirst()
                .orElseThrow(() -> new IdempotencyConflictException(request.idempotencyKey(),
                        "Stored transaction has no debit entry"));
        LedgerEntry credit = entries.stream()
                .filter(entry -> entry.direction() == Direction.CREDIT)
                .findFirst()
                .orElseThrow(() -> new IdempotencyConflictException(request.idempotencyKey(),
                        "Stored transaction has no credit entry"));

        return new TransferResult(existing.transactionId(), existing.idempotencyKey(),
                existing.status().name(), Math.abs(debit.signedAmountMinor()), existing.currency(),
                debit.runningBalanceMinor(), credit.runningBalanceMinor(), existing.createdAt(), true);
    }

    private void verifySamePayload(TransactionEntity existing, TransferRequest request) {
        try {
            JsonNode metadata = objectMapper.readTree(existing.metadata());
            boolean same = metadata.path("sourceAccountId").asText().equals(request.sourceAccountId().toString())
                    && metadata.path("destinationAccountId").asText().equals(request.destinationAccountId().toString())
                    && metadata.path("amountMinor").asLong() == request.amountMinor()
                    && metadata.path("currency").asText().equals(request.currency());
            if (!same || existing.txType() == TxType.REVERSAL) {
                throw new IdempotencyConflictException(request.idempotencyKey(),
                        "Idempotency key was already used with a different payload");
            }
        } catch (IdempotencyConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IdempotencyConflictException(request.idempotencyKey(),
                    "Stored transaction metadata cannot be compared");
        }
    }

    private String metadata(TransferRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceAccountId", request.sourceAccountId().toString());
        values.put("destinationAccountId", request.destinationAccountId().toString());
        values.put("amountMinor", request.amountMinor());
        values.put("currency", request.currency());
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize transaction metadata", exception);
        }
    }

    private OutboxEvent outboxEvent(UUID transactionId,
                                    String eventType,
                                    TransferRequest request,
                                    long sourceBalanceAfter,
                                    long destinationBalanceAfter,
                                    Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", eventType);
        payload.put("transactionId", transactionId.toString());
        payload.put("sourceAccountId", request.sourceAccountId().toString());
        payload.put("destinationAccountId", request.destinationAccountId().toString());
        payload.put("amountMinor", request.amountMinor());
        payload.put("currency", request.currency());
        payload.put("sourceBalanceAfter", sourceBalanceAfter);
        payload.put("destinationBalanceAfter", destinationBalanceAfter);
        payload.put("occurredAt", occurredAt.toString());
        try {
            return new OutboxEvent(eventId, "TRANSACTION", transactionId, eventType,
                    objectMapper.writeValueAsString(payload), 0, occurredAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize outbox payload", exception);
        }
    }

    private long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new InvalidAmountException("Amount overflows the supported minor-unit range");
        }
    }

    private long subtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new InvalidAmountException("Amount overflows the supported minor-unit range");
        }
    }
}
