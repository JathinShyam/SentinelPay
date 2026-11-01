package com.sentinelpay;

import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.domain.TransferRequest;
import com.sentinelpay.ledger.service.LedgerTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerInvariantIT extends AbstractIntegrationTest {

    @Autowired
    LedgerTransferService transferService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyMaterializedBalanceEqualsItsJournalSumAfterMixedConcurrency() throws Exception {
        List<AccountView> accounts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            accounts.add(createAccount("invariant-" + UUID.randomUUID(), "INR", 1_000_000));
        }

        int transfers = 240;
        Random random = new Random(42);
        List<TransferRequest> requests = new ArrayList<>();
        for (int i = 0; i < transfers; i++) {
            int sourceIndex = random.nextInt(accounts.size());
            int destinationIndex;
            do {
                destinationIndex = random.nextInt(accounts.size());
            } while (destinationIndex == sourceIndex);
            requests.add(new TransferRequest("invariant-" + UUID.randomUUID(),
                    accounts.get(sourceIndex).accountId(), accounts.get(destinationIndex).accountId(),
                    100 + random.nextInt(2_000), "INR", "mixed workload " + i));
        }

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (TransferRequest request : requests) {
                futures.add(executor.submit(() -> {
                    start.await();
                    transferService.executeTransfer(request);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        List<Map<String, Object>> drift = jdbc.queryForList("""
                SELECT a.account_id, a.balance_minor,
                       COALESCE(SUM(e.signed_amount_minor), 0) AS ledger_balance
                FROM accounts a
                LEFT JOIN ledger_entries e ON e.account_id = a.account_id
                GROUP BY a.account_id, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(e.signed_amount_minor), 0)
                """);
        assertThat(drift).as("no account may drift from its journal").isEmpty();

        List<Map<String, Object>> unbalanced = jdbc.queryForList("""
                SELECT transaction_id, SUM(signed_amount_minor) AS total
                FROM ledger_entries
                GROUP BY transaction_id
                HAVING SUM(signed_amount_minor) <> 0
                """);
        assertThat(unbalanced).as("every transaction must sum to zero").isEmpty();
    }
}
