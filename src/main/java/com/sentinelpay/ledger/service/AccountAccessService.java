package com.sentinelpay.ledger.service;

import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.common.exception.AccountNotFoundException;
import com.sentinelpay.ledger.domain.Account;
import com.sentinelpay.ledger.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AccountAccessService {

    private final AccountRepository accounts;

    public AccountAccessService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Loads an account only when the caller may see it. A foreign account is
     * reported as 404 rather than 403 so account identifiers cannot be probed.
     */
    public Account requireVisible(UUID accountId, AuthenticatedUser user) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!isVisible(account, user)) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }

    public boolean isVisible(UUID accountId, AuthenticatedUser user) {
        return accounts.findById(accountId)
                .map(account -> isVisible(account, user))
                .orElse(false);
    }

    private boolean isVisible(Account account, AuthenticatedUser user) {
        return user.isAdmin() || account.ownerId().equals(user.userId().toString());
    }
}
