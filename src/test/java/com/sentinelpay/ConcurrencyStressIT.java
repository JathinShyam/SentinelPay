package com.sentinelpay;

import com.sentinelpay.common.exception.InsufficientFundsException;
import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.domain.TransferRequest;
import com.sentinelpay.ledger.service.LedgerAuditService;
import com.sentinelpay.ledger.service.LedgerTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 verification gate.
 *
 * <p>Test 1 proves the balance check happens after locks (exactly ten of fifty
 * 1,000-minor debits succeed against a 10,000 balance). Test 2 proves deterministic
 * UUID lock ordering makes bidirectional deadlocks impossible.
 */
class ConcurrencyStressIT extends AbstractIntegrationTest {

    @Autowired
    LedgerTransferService transferService;

    @Autowired
    LedgerAuditService auditService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void overdraftRaceAllowsExactlyTheFundedNumberOfTransfers() throws Exception {
        AccountView source = createAccount("overdraft-" + UUID.randomUUID(), "INR", 10_000);
        AccountView destination = createAccount("overdraft-" + UUID.randomUUID(), "INR", 0);

        int attempts = 50;
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        runConcurrently(attempts, index -> {
            try {
                transferService.executeTransfer(new TransferRequest(
                        "overdraft-" + UUID.randomUUID(),
                        source.accountId(),
                        destination.accountId(),
                        1_000,
                        "INR",
                        "attempt " + index));
                successes.incrementAndGet();
            } catch (InsufficientFundsException exception) {
                insufficient.incrementAndGet();
            }
        }, unexpected);

        assertThat(unexpected).as("no unexpected failures or deadlocks").isEmpty();
        assertThat(successes.get()).as("exactly ten funded transfers succeed").isEqualTo(10);
        assertThat(insufficient.get()).as("the remaining forty see insufficient funds").isEqualTo(40);

        assertThat(balanceOf(source.accountId())).isZero();
        assertThat(balanceOf(destination.accountId())).isEqualTo(10_000L);
        assertThat(auditService.auditAccount(source.accountId()).driftMinor()).isZero();
        assertThat(auditService.auditAccount(destination.accountId()).driftMinor()).isZero();
    }

    @Test
    void bidirectionalTransfersCompleteWithoutDeadlocksAndPreserveNetBalances() throws Exception {
        AccountView a = createAccount("deadlock-a-" + UUID.randomUUID(), "INR", 1_000_000);
        AccountView b = createAccount("deadlock-b-" + UUID.randomUUID(), "INR", 1_000_000);

        long beforeA = balanceOf(a.accountId());
        long beforeB = balanceOf(b.accountId());
        List<Throwable> unexpected = new ArrayList<>();

        int perDirection = 100;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < perDirection; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    transferService.executeTransfer(new TransferRequest(
                            "a-to-b-" + UUID.randomUUID(), a.accountId(), b.accountId(),
                            100, "INR", "A->B"));
                    return null;
                }));
                futures.add(executor.submit(() -> {
                    start.await();
                    transferService.executeTransfer(new TransferRequest(
                            "b-to-a-" + UUID.randomUUID(), b.accountId(), a.accountId(),
                            100, "INR", "B->A"));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    unexpected.add(exception.getCause());
                }
            }
        }

        assertThat(unexpected).as("zero deadlocks and zero unexpected failures").isEmpty();
        assertThat(balanceOf(a.accountId())).isEqualTo(beforeA);
        assertThat(balanceOf(b.accountId())).isEqualTo(beforeB);
        assertThat(auditService.auditAccount(a.accountId()).driftMinor()).isZero();
        assertThat(auditService.auditAccount(b.accountId()).driftMinor()).isZero();
    }

    private void runConcurrently(int count,
                                 ThrowingIntConsumer task,
                                 List<Throwable> unexpected) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        task.accept(index);
                    } catch (Throwable throwable) {
                        unexpected.add(throwable);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
    }

    private long balanceOf(UUID accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class, accountId);
        return balance == null ? 0L : balance;
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int value) throws Exception;
    }
}
