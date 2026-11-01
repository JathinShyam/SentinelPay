package com.sentinelpay.ledger.api;

import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<AccountView> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountView account = accountService.createAccount(request.ownerId(), request.currency(),
                request.initialDepositOrZero());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @GetMapping("/accounts/{accountId}")
    public AccountView get(@PathVariable UUID accountId) {
        return accountService.getAccount(accountId);
    }

    @GetMapping("/owners/{ownerId}/accounts")
    public List<AccountView> byOwner(@PathVariable String ownerId) {
        return accountService.getAccountsByOwner(ownerId);
    }
}
