package com.sentinelpay.ledger.service;

import com.sentinelpay.common.exception.ApiErrorCode;
import com.sentinelpay.common.exception.ApiException;
import com.sentinelpay.common.exception.InvalidAmountException;
import com.sentinelpay.ledger.domain.Account;
import com.sentinelpay.ledger.domain.AccountStatus;
import com.sentinelpay.ledger.domain.AccountType;
import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountService {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("INR", "USD", "EUR");

    private final AccountRepository accountRepository;
    private final LedgerTransferService transferService;

    public AccountService(AccountRepository accountRepository, LedgerTransferService transferService) {
        this.accountRepository = accountRepository;
        this.transferService = transferService;
    }

    @Transactional
    public AccountView createAccount(String ownerId, String currency, long initialDepositMinor) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 64) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "ownerId must be non-blank and at most 64 characters");
        }
        if (currency == null || !SUPPORTED_CURRENCIES.contains(currency)) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "currency must be one of INR, USD, EUR");
        }
        if (initialDepositMinor < 0) {
            throw new InvalidAmountException("initialDepositMinor cannot be negative");
        }

        Instant now = Instant.now();
        UUID accountId = UUID.randomUUID();
        Account account = new Account(accountId, ownerId, currency, 0L,
                AccountType.USER, AccountStatus.ACTIVE, 0L, now, now);
        accountRepository.insert(account);

        if (initialDepositMinor > 0) {
            transferService.executeSystemDeposit(
                    SystemAccounts.reserve(currency),
                    accountId,
                    initialDepositMinor,
                    currency,
                    "deposit:" + accountId,
                    "Initial account funding");
        }

        return AccountView.from(accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Newly created account disappeared")));
    }

    @Transactional(readOnly = true)
    public AccountView getAccount(UUID accountId) {
        return AccountView.from(accountRepository.findById(accountId)
                .orElseThrow(() -> new com.sentinelpay.common.exception.AccountNotFoundException(accountId)));
    }

    @Transactional(readOnly = true)
    public List<AccountView> getAccountsByOwner(String ownerId) {
        return accountRepository.findByOwner(ownerId).stream()
                .map(AccountView::from)
                .toList();
    }
}
