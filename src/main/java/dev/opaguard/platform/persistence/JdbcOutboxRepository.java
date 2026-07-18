package dev.opaguard.platform.persistence;

import dev.opaguard.platform.port.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL transactional-outbox adapter using leased {@code SKIP LOCKED} claims.
 *
 * @author Shelton Bumhe
 */
@Repository
@ConditionalOnExpression("'${opa-guard.mode:cli}' == 'coordinator' or '${opa-guard.mode:cli}' == 'worker' or '${opa-guard.mode:cli}' == 'analyzer'")
public class JdbcOutboxRepository implements OutboxRepository {
    private final JdbcClient jdbc;

    public JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxMessage message) {
        jdbc.sql("""
                INSERT INTO outbox_events
                  (event_id, aggregate_id, organization_id, topic, event_type, partition_key, payload, created_at)
                VALUES (:event, :aggregate, :org, :topic, :type, :key, CAST(:payload AS jsonb), :created)
                ON CONFLICT (event_id) DO NOTHING
                """).param("event", message.eventId()).param("aggregate", message.aggregateId())
                .param("org", message.organizationId()).param("topic", message.topic())
                .param("type", message.eventType()).param("key", message.partitionKey())
                .param("payload", message.payload()).param("created", Timestamp.from(message.createdAt())).update();
    }

    @Override
    public List<OutboxMessage> claimBatch(int limit) {
        return jdbc.sql("""
                UPDATE outbox_events SET locked_until=now()+interval '30 seconds', attempts=attempts+1
                WHERE event_id IN (
                  SELECT event_id FROM outbox_events
                  WHERE published_at IS NULL AND (locked_until IS NULL OR locked_until < now()) AND attempts < 20
                  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT :limit
                )
                RETURNING event_id, aggregate_id, organization_id, topic, event_type, partition_key,
                          payload::text, created_at, attempts
                """).param("limit", Math.min(Math.max(limit, 1), 1000))
                .query((rs, row) -> new OutboxMessage(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8).toInstant(), rs.getInt(9))).list();
    }

    @Override
    public long unpublishedCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class).single();
    }

    @Override
    public void markPublished(UUID eventId, Instant publishedAt) {
        jdbc.sql("UPDATE outbox_events SET published_at=:at, locked_until=NULL, last_error=NULL WHERE event_id=:id")
                .param("at", Timestamp.from(publishedAt)).param("id", eventId).update();
    }

    @Override
    public void release(UUID eventId, String error) {
        String bounded = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 1000));
        jdbc.sql("UPDATE outbox_events SET locked_until=NULL, last_error=:error WHERE event_id=:id")
                .param("error", bounded).param("id", eventId).update();
    }
}
