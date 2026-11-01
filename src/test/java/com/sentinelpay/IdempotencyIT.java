package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.ledger.domain.AccountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends AbstractIntegrationTest {

    private static final String KEY_PREFIX = "sentinelpay:idempotency:";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void sequentialReplayReturnsTheSameTransactionAndCachesItFor24Hours() {
        AccountView source = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 20_000);
        AccountView destination = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 0);
        String key = "idem-seq-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = transfer(source.accountId(), destination.accountId(),
                5_000, "INR", key);
        ResponseEntity<JsonNode> second = transfer(source.accountId(), destination.accountId(),
                5_000, "INR", key);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.getBody().path("transactionId").asText())
                .isEqualTo(first.getBody().path("transactionId").asText());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class, source.accountId()))
                .isEqualTo(15_000L);

        Long ttl = redis.getExpire(KEY_PREFIX + key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(TimeUnit.HOURS.toSeconds(23));
    }

    @Test
    void twentyConcurrentIdenticalRequestsProduceOneDebit() throws Exception {
        AccountView source = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 20_000);
        AccountView destination = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 0);
        String key = "idem-race-" + UUID.randomUUID();

        int concurrency = 20;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return transfer(source.accountId(), destination.accountId(), 1_000, "INR", key)
                            .getStatusCode().value();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
        assertThat(statuses).allMatch(status -> status == 201 || status == 200 || status == 409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class, source.accountId()))
                .isEqualTo(19_000L);
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class,
                destination.accountId()))
                .isEqualTo(1_000L);
    }

    @Test
    void sameKeyWithDifferentPayloadReturns409() {
        AccountView source = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 20_000);
        AccountView destination = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 0);
        String key = "idem-conflict-" + UUID.randomUUID();

        assertThat(transfer(source.accountId(), destination.accountId(), 1_000, "INR", key)
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<JsonNode> conflicting = transfer(source.accountId(), destination.accountId(),
                2_000, "INR", key);

        assertProblem(conflicting, 409, "IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1L);
    }

    @Test
    void deterministic4xxIsCachedWithTheReplayHeader() {
        AccountView source = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 100);
        AccountView destination = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 0);
        String key = "idem-4xx-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = transfer(source.accountId(), destination.accountId(),
                1_000, "INR", key);
        ResponseEntity<JsonNode> replay = transfer(source.accountId(), destination.accountId(),
                1_000, "INR", key);

        assertProblem(first, 422, "INSUFFICIENT_FUNDS");
        assertProblem(replay, 422, "INSUFFICIENT_FUNDS");
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(redis.getExpire(KEY_PREFIX + key, TimeUnit.SECONDS))
                .isGreaterThan(TimeUnit.HOURS.toSeconds(23));
    }

    @Test
    void serverErrorReleasesTheRedisLockSoTheClientCanRetry() {
        AccountView source = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 10_000);
        AccountView destination = createAccount("idem-owner-" + UUID.randomUUID(), "INR", 0);
        ResponseEntity<JsonNode> posted = transfer(source.accountId(), destination.accountId(),
                500, "INR", "idem-seed-" + UUID.randomUUID());
        UUID transactionId = UUID.fromString(posted.getBody().path("transactionId").asText());

        // A reversal envelope without entries makes the service raise a 500 while replaying.
        jdbc.update("""
                        INSERT INTO transactions
                            (transaction_id, idempotency_key, tx_type, status, currency,
                             description, metadata, reversal_of)
                        VALUES (?, ?, 'REVERSAL', 'POSTED', 'INR', 'broken', '{}'::jsonb, ?)
                        """,
                UUID.randomUUID(), "idem-broken-" + UUID.randomUUID(), transactionId);

        String key = "idem-5xx-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = reverse(transactionId, key);
        assertThat(first.getStatusCode().value()).isEqualTo(500);
        assertThat(redis.hasKey(KEY_PREFIX + key)).isFalse();

        ResponseEntity<JsonNode> second = reverse(transactionId, key);
        assertThat(second.getStatusCode().value()).isEqualTo(500);
    }
}
