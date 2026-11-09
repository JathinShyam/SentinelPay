package com.sentinelpay.ledger.api;

import com.sentinelpay.auth.security.AuthenticatedUser;
import com.sentinelpay.ledger.domain.AuditCertificate;
import com.sentinelpay.ledger.service.AccountAccessService;
import com.sentinelpay.ledger.service.LedgerAuditService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final LedgerAuditService auditService;
    private final AccountAccessService accountAccess;

    public AuditController(LedgerAuditService auditService,
                           AccountAccessService accountAccess) {
        this.auditService = auditService;
        this.accountAccess = accountAccess;
    }

    @GetMapping("/accounts/{accountId}/audit")
    public AuditCertificate audit(@PathVariable UUID accountId,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        accountAccess.requireVisible(accountId, user);
        return auditService.auditAccount(accountId);
    }
}
