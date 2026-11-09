package com.sentinelpay.wallet.api;

import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.wallet.service.WalletService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ActivityController {

    private final WalletService walletService;

    public ActivityController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public ActivityPage activity(@PathVariable UUID accountId,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String cursor,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.activity(accountId, limit, cursor, user);
    }

    @GetMapping("/transactions/{transactionId}")
    public TransactionDetailView transaction(@PathVariable UUID transactionId,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.transactionDetail(transactionId, user);
    }
}
