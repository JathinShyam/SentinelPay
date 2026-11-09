package com.sentinelpay.wallet.api;

import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.wallet.service.WalletService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/overview")
    public WalletOverview overview(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.overview(user);
    }
}
