package com.sentinelpay.ledger.service;

import com.sentinelpay.common.exception.AccountNotFoundException;
import com.sentinelpay.ledger.domain.Account;
import com.sentinelpay.ledger.domain.AuditCertificate;
import com.sentinelpay.ledger.repository.AccountRepository;
import com.sentinelpay.ledger.repository.LedgerEntryRepository;
import com.sentinelpay.ledger.repository.LedgerEntryRepository.AccountLedgerSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class LedgerAuditService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerAuditService(AccountRepository accountRepository,
                              LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public AuditCertificate auditAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        AccountLedgerSummary summary = ledgerEntryRepository.summarizeAccount(accountId);

        return new AuditCertificate(
                accountId,
                account.balanceMinor(),
                summary.balanceMinor(),
                account.balanceMinor() - summary.balanceMinor(),
                summary.entryCount(),
                summary.balanced(),
                Instant.now());
    }
}
