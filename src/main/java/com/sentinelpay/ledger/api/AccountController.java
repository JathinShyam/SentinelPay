package com.sentinelpay.ledger.api;

import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.common.exception.AccountNotFoundException;
import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                              @Valid @RequestBody CreateAccountRequest request) {
        AccountView account = accountService.createAccount(user.userId().toString(),
                request.currency(), request.initialDepositOrZero());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountView get(@PathVariable UUID accountId,
                           @AuthenticationPrincipal AuthenticatedUser user) {
        AccountView account = accountService.getAccount(accountId);
        requireVisible(account, user);
        return account;
    }

    @GetMapping("/me/accounts")
    public List<AccountView> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.getAccountsByOwner(user.userId().toString());
    }

    private void requireVisible(AccountView account, AuthenticatedUser user) {
        if (!user.isAdmin() && !account.ownerId().equals(user.userId().toString())) {
            throw new AccountNotFoundException(account.accountId());
        }
    }
}
