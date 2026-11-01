package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.domain.AuditCertificate;
import com.sentinelpay.ledger.domain.TxStatus;
import com.sentinelpay.ledger.repository.TransactionRepository;
import com.sentinelpay.ledger.service.LedgerAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalAuditIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    LedgerAuditService auditService;

    @Test
    void reversalCreatesInverseEntriesAndOriginalStatusIsDerivedWithoutMutation() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 100_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);

        ResponseEntity<JsonNode> transfer = transfer(source.accountId(), destination.accountId(),
                20_000, "INR", "to-reverse-" + UUID.randomUUID());
        UUID originalId = UUID.fromString(transfer.getBody().path("transactionId").asText());

        ResponseEntity<JsonNode> reversal = reverse(originalId, "reversal-" + UUID.randomUUID());

        assertThat(reversal.getStatusCode().value()).isEqualTo(201);
        UUID reversalId = UUID.fromString(reversal.getBody().path("transactionId").asText());
        assertThat(reversal.getBody().path("originalTransactionId").asText()).isEqualTo(originalId.toString());

        assertThat(jdbc.queryForObject("""
                SELECT signed_amount_minor FROM ledger_entries
                WHERE transaction_id = ? AND account_id = ? AND direction = 'DEBIT'
                """, Long.class, reversalId, destination.accountId()))
                .isEqualTo(-20_000L);
        assertThat(jdbc.queryForObject("""
                SELECT signed_amount_minor FROM ledger_entries
                WHERE transaction_id = ? AND account_id = ? AND direction = 'CREDIT'
                """, Long.class, reversalId, source.accountId()))
                .isEqualTo(20_000L);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM transactions WHERE transaction_id = ?", String.class, originalId))
                .as("the append-only original row is never mutated")
                .isEqualTo("POSTED");
        assertThat(transactionRepository.findById(originalId).orElseThrow().status())
                .as("status REVERSED is derived from the linked reversal")
                .isEqualTo(TxStatus.REVERSED);

        assertThat(auditService.auditAccount(source.accountId()).driftMinor()).isZero();
        assertThat(auditService.auditAccount(destination.accountId()).driftMinor()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class, source.accountId()))
                .isEqualTo(100_000L);
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class,
                destination.accountId()))
                .isZero();
    }

    @Test
    void auditCertificateProvesLinkedTransactionsBalanceAcrossAccounts() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 100_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);

        transfer(source.accountId(), destination.accountId(), 25_000, "INR",
                "audit-balance-" + UUID.randomUUID());

        AuditCertificate sourceAudit = auditService.auditAccount(source.accountId());
        assertThat(sourceAudit.materializedBalanceMinor()).isEqualTo(75_000L);
        assertThat(sourceAudit.ledgerBalanceMinor()).isEqualTo(75_000L);
        assertThat(sourceAudit.driftMinor()).isZero();
        assertThat(sourceAudit.entryCount()).isEqualTo(2L);
        assertThat(sourceAudit.transactionsBalanced())
                .as("deposit and transfer entries must balance across both accounts")
                .isTrue();

        AuditCertificate destinationAudit = auditService.auditAccount(destination.accountId());
        assertThat(destinationAudit.materializedBalanceMinor()).isEqualTo(25_000L);
        assertThat(destinationAudit.ledgerBalanceMinor()).isEqualTo(25_000L);
        assertThat(destinationAudit.driftMinor()).isZero();
        assertThat(destinationAudit.entryCount()).isEqualTo(1L);
        assertThat(destinationAudit.transactionsBalanced()).isTrue();
    }

    @Test
    void doubleReversalIsIdempotent() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 50_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);
        UUID originalId = UUID.fromString(transfer(source.accountId(), destination.accountId(),
                10_000, "INR", "double-" + UUID.randomUUID()).getBody().path("transactionId").asText());

        String key = "reverse-once-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = reverse(originalId, key);
        ResponseEntity<JsonNode> sameKey = reverse(originalId, key);
        ResponseEntity<JsonNode> differentKey = reverse(originalId, "reverse-twice-" + UUID.randomUUID());

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(sameKey.getStatusCode().value()).isEqualTo(200);
        assertThat(differentKey.getStatusCode().value()).isEqualTo(200);
        assertThat(sameKey.getBody().path("transactionId").asText())
                .isEqualTo(first.getBody().path("transactionId").asText());
        assertThat(differentKey.getBody().path("transactionId").asText())
                .isEqualTo(first.getBody().path("transactionId").asText());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM transactions WHERE reversal_of = ?
                """, Long.class, originalId))
                .isEqualTo(1L);
    }

    @Test
    void unknownTransactionReturns404() {
        assertProblem(reverse(UUID.randomUUID(), "unknown-" + UUID.randomUUID()),
                404, "TRANSACTION_NOT_FOUND");
    }

    @Test
    void reversalFailsWhenOriginalDestinationAlreadySpentTheFunds() {
        AccountView a = createAccount("owner-" + UUID.randomUUID(), "INR", 50_000);
        AccountView b = createAccount("owner-" + UUID.randomUUID(), "INR", 0);
        AccountView c = createAccount("owner-" + UUID.randomUUID(), "INR", 0);

        UUID originalId = UUID.fromString(transfer(a.accountId(), b.accountId(), 40_000, "INR",
                "spend-source-" + UUID.randomUUID()).getBody().path("transactionId").asText());
        transfer(b.accountId(), c.accountId(), 35_000, "INR", "spend-" + UUID.randomUUID());

        assertProblem(reverse(originalId, "cannot-reverse-" + UUID.randomUUID()),
                422, "INSUFFICIENT_FUNDS");
    }
}
