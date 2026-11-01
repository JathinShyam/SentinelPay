package com.sentinelpay.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.exception.AccountNotActiveException;
import com.sentinelpay.common.exception.AccountNotFoundException;
import com.sentinelpay.common.exception.IdempotencyConflictException;
import com.sentinelpay.common.exception.InsufficientFundsException;
import com.sentinelpay.common.exception.ReversalNotAllowedException;
import com.sentinelpay.common.exception.TransactionNotFoundException;
import com.sentinelpay.ledger.domain.Account;
import com.sentinelpay.ledger.domain.AccountStatus;
import com.sentinelpay.ledger.domain.Direction;
import com.sentinelpay.ledger.domain.LedgerEntry;
import com.sentinelpay.ledger.domain.ReversalResult;
import com.sentinelpay.ledger.domain.TransactionEntity;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReversalService {

    private static final String REVERSED_EVENT = "TransferReversedEvent";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public ReversalService(AccountRepository accountRepository,
                           TransactionRepository transactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           OutboxRepository outboxRepository,
                           ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReversalResult reverse(UUID originalTransactionId, String idempotencyKey, String description) {
        TransactionEntity byKey = transactionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (byKey != null) {
            if (byKey.txType() != TxType.REVERSAL
                    || !originalTransactionId.equals(byKey.reversalOf())) {
                throw new IdempotencyConflictException(idempotencyKey,
                        "Idempotency key was already used for a different operation");
            }
            return replay(byKey);
        }

        TransactionEntity original = transactionRepository.findByIdForUpdate(originalTransactionId)
                .orElseThrow(() -> new TransactionNotFoundException(originalTransactionId));

        if (original.txType() == TxType.REVERSAL) {
            throw new ReversalNotAllowedException(originalTransactionId,
                    "a reversal transaction cannot itself be reversed");
        }

        TransactionEntity existingReversal = transactionRepository.findReversalOf(originalTransactionId)
                .orElse(null);
        if (existingReversal != null) {
            return replay(existingReversal);
        }

        List<LedgerEntry> originalEntries =
                ledgerEntryRepository.findByTransactionId(originalTransactionId);
        LedgerEntry originalDebit = originalEntries.stream()
                .filter(entry -> entry.direction() == Direction.DEBIT)
                .findFirst()
                .orElseThrow(() -> new ReversalNotAllowedException(originalTransactionId,
                        "original transaction has no debit entry"));
        LedgerEntry originalCredit = originalEntries.stream()
                .filter(entry -> entry.direction() == Direction.CREDIT)
                .findFirst()
                .orElseThrow(() -> new ReversalNotAllowedException(originalTransactionId,
                        "original transaction has no credit entry"));

        UUID reversalSourceId = originalCredit.accountId();
        UUID reversalDestinationId = originalDebit.accountId();
        long amountMinor = Math.abs(originalDebit.signedAmountMinor());
        String currency = original.currency();

        UUID firstLockId = reversalSourceId.compareTo(reversalDestinationId) < 0
                ? reversalSourceId : reversalDestinationId;
        UUID secondLockId = firstLockId.equals(reversalSourceId)
                ? reversalDestinationId : reversalSourceId;

        Account first = accountRepository.findByIdForUpdate(firstLockId)
                .orElseThrow(() -> new AccountNotFoundException(firstLockId));
        Account second = accountRepository.findByIdForUpdate(secondLockId)
                .orElseThrow(() -> new AccountNotFoundException(secondLockId));

        Account reversalSource = firstLockId.equals(reversalSourceId) ? first : second;
        Account reversalDestination = firstLockId.equals(reversalDestinationId) ? first : second;

        requireActive(reversalSource);
        requireActive(reversalDestination);

        if (!reversalSource.isSystem() && reversalSource.balanceMinor() < amountMinor) {
            throw new InsufficientFundsException(reversalSource.accountId(),
                    reversalSource.balanceMinor(), amountMinor);
        }

        long newSourceBalance = reversalSource.balanceMinor() - amountMinor;
        long newDestinationBalance = reversalDestination.balanceMinor() + amountMinor;

        Instant now = Instant.now();
        UUID reversalTransactionId = UUID.randomUUID();
        TransactionEntity reversal = new TransactionEntity(
                reversalTransactionId,
                idempotencyKey,
                TxType.REVERSAL,
                TxStatus.POSTED,
                currency,
                description == null ? "Reversal of " + originalTransactionId : description,
                reversalMetadata(originalTransactionId, reversalSourceId, reversalDestinationId,
                        amountMinor, currency),
                originalTransactionId,
                now);

        if (!transactionRepository.insertIfAbsent(reversal)) {
            TransactionEntity winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IdempotencyConflictException(idempotencyKey,
                            "Concurrent reversal won but is not readable"));
            return replay(winner);
        }

        updateBalance(first, firstLockId.equals(reversalSourceId) ? newSourceBalance : newDestinationBalance);
        updateBalance(second, secondLockId.equals(reversalSourceId) ? newSourceBalance : newDestinationBalance);

        ledgerEntryRepository.batchInsert(List.of(
                new LedgerEntry(UUID.randomUUID(), reversalTransactionId, reversalSourceId,
                        Direction.DEBIT, -amountMinor, newSourceBalance, now),
                new LedgerEntry(UUID.randomUUID(), reversalTransactionId, reversalDestinationId,
                        Direction.CREDIT, amountMinor, newDestinationBalance, now)));

        outboxRepository.insert(reversalOutbox(reversalTransactionId, originalTransactionId,
                reversalSourceId, reversalDestinationId, amountMinor, currency,
                newSourceBalance, newDestinationBalance, now));

        return new ReversalResult(reversalTransactionId, originalTransactionId, idempotencyKey,
                TxStatus.POSTED.name(), amountMinor, currency, newSourceBalance,
                newDestinationBalance, now, false);
    }

    private ReversalResult replay(TransactionEntity reversal) {
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(reversal.transactionId());
        LedgerEntry debit = entries.stream()
                .filter(entry -> entry.direction() == Direction.DEBIT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Reversal has no debit entry"));
        LedgerEntry credit = entries.stream()
                .filter(entry -> entry.direction() == Direction.CREDIT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Reversal has no credit entry"));
        return new ReversalResult(reversal.transactionId(), reversal.reversalOf(),
                reversal.idempotencyKey(), reversal.status().name(),
                Math.abs(debit.signedAmountMinor()), reversal.currency(),
                debit.runningBalanceMinor(), credit.runningBalanceMinor(),
                reversal.createdAt(), true);
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

    private String reversalMetadata(UUID originalTransactionId,
                                    UUID sourceAccountId,
                                    UUID destinationAccountId,
                                    long amountMinor,
                                    String currency) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("originalTransactionId", originalTransactionId.toString());
        values.put("sourceAccountId", sourceAccountId.toString());
        values.put("destinationAccountId", destinationAccountId.toString());
        values.put("amountMinor", amountMinor);
        values.put("currency", currency);
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize reversal metadata", exception);
        }
    }

    private OutboxEvent reversalOutbox(UUID reversalTransactionId,
                                       UUID originalTransactionId,
                                       UUID sourceAccountId,
                                       UUID destinationAccountId,
                                       long amountMinor,
                                       String currency,
                                       long sourceBalanceAfter,
                                       long destinationBalanceAfter,
                                       Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", REVERSED_EVENT);
        payload.put("transactionId", reversalTransactionId.toString());
        payload.put("originalTransactionId", originalTransactionId.toString());
        payload.put("sourceAccountId", sourceAccountId.toString());
        payload.put("destinationAccountId", destinationAccountId.toString());
        payload.put("amountMinor", amountMinor);
        payload.put("currency", currency);
        payload.put("sourceBalanceAfter", sourceBalanceAfter);
        payload.put("destinationBalanceAfter", destinationBalanceAfter);
        payload.put("occurredAt", occurredAt.toString());
        try {
            return new OutboxEvent(eventId, "TRANSACTION", reversalTransactionId,
                    REVERSED_EVENT, objectMapper.writeValueAsString(payload), 0, occurredAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize reversal outbox payload", exception);
        }
    }
}
