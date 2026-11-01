package com.sentinelpay.ledger.api;

import com.sentinelpay.ledger.domain.AuditCertificate;
import com.sentinelpay.ledger.service.LedgerAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final LedgerAuditService auditService;

    public AuditController(LedgerAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/accounts/{accountId}/audit")
    public AuditCertificate audit(@PathVariable UUID accountId) {
        return auditService.auditAccount(accountId);
    }
}
