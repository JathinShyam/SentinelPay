package com.sentinelpay.outbox.repository;

import com.sentinelpay.outbox.domain.OutboxEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(
            UUID.fromString(rs.getString("event_id")),
            rs.getString("aggregate_type"),
            UUID.fromString(rs.getString("aggregate_id")),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getInt("retry_count"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the oldest deliverable events for the relay. The caller must keep the
     * surrounding transaction open until the send result is recorded, so a second
     * relay instance cannot claim the same rows.
     */
    public List<OutboxEvent> claimPending(int batchSize) {
        return jdbc.sql("""
                        SELECT event_id, aggregate_type, aggregate_id, event_type,
                               payload::text AS payload, retry_count, created_at
                        FROM outbox_events
                        WHERE status = 'PENDING' AND next_attempt_at <= NOW()
                        ORDER BY created_at ASC
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("batchSize", batchSize)
                .query(ROW_MAPPER)
                .list();
    }

    public void markProcessed(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE outbox_events
                        SET status = 'PROCESSED', processed_at = NOW()
                        WHERE event_id IN (:eventIds) AND status = 'PENDING'
                        """)
                .param("eventIds", eventIds)
                .update();
    }

    public void markForRetry(UUID eventId, int retryCount, long delaySeconds) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET retry_count = :retryCount,
                            next_attempt_at = NOW() + make_interval(secs => CAST(:delaySeconds AS double precision))
                        WHERE event_id = :eventId AND status = 'PENDING'
                        """)
                .param("eventId", eventId)
                .param("retryCount", retryCount)
                .param("delaySeconds", delaySeconds)
                .update();
    }

    public void markDeadLetter(UUID eventId, int retryCount) {
        jdbc.sql("""
                        UPDATE outbox_events
                        SET status = 'DEAD_LETTER', retry_count = :retryCount
                        WHERE event_id = :eventId AND status = 'PENDING'
                        """)
                .param("eventId", eventId)
                .param("retryCount", retryCount)
                .update();
    }

    public Optional<OutboxEvent> findById(UUID eventId) {
        return jdbc.sql("""
                        SELECT event_id, aggregate_type, aggregate_id, event_type,
                               payload::text AS payload, retry_count, created_at
                        FROM outbox_events
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(ROW_MAPPER)
                .optional();
    }

    public void insert(OutboxEvent event) {
        jdbc.sql("""
                        INSERT INTO outbox_events
                            (event_id, aggregate_type, aggregate_id, event_type, payload,
                             status, retry_count, next_attempt_at, created_at)
                        VALUES
                            (:eventId, :aggregateType, :aggregateId, :eventType,
                             CAST(:payload AS jsonb), 'PENDING', 0, NOW(), :createdAt)
                        """)
                .param("eventId", event.eventId())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("eventType", event.eventType())
                .param("payload", event.payload())
                .param("createdAt", java.sql.Timestamp.from(event.createdAt()))
                .update();
    }

    public long countPending() {
        Long count = jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'")
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    public Long oldestPendingAgeSeconds() {
        return jdbc.sql("""
                        SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0)::bigint
                        FROM outbox_events
                        WHERE status = 'PENDING'
                        """)
                .query(Long.class)
                .single();
    }
}
