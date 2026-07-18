package dev.opaguard.platform.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox port providing at-least-once event publication.
 *
 * @author Shelton Bumhe
 */
public interface OutboxRepository {
    /**
     * Appends an event in the caller's database transaction.
     *
     * @param message event envelope
     */
    void append(OutboxMessage message);

    /**
     * Claims an ordered batch for exclusive relay processing.
     *
     * @param limit maximum number of messages
     * @return claimed unpublished messages
     */
    List<OutboxMessage> claimBatch(int limit);

    /**
     * Returns the current unpublished event count for operational telemetry.
     *
     * @return unpublished outbox rows
     */
    long unpublishedCount();

    /**
     * Marks an event as published.
     *
     * @param eventId event identifier
     * @param publishedAt broker acknowledgment time
     */
    void markPublished(UUID eventId, Instant publishedAt);

    /**
     * Releases a failed claim for a bounded retry.
     *
     * @param eventId event identifier
     * @param error sanitized failure description
     */
    void release(UUID eventId, String error);

    /**
     * Durable event envelope stored by the transactional outbox.
     *
     * @param eventId globally unique event identifier
     * @param aggregateId originating aggregate identifier
     * @param organizationId owning tenant
     * @param topic destination topic
     * @param eventType schema type name
     * @param partitionKey stable ordering key
     * @param payload serialized event JSON
     * @param createdAt creation timestamp
     * @param attempts prior publish attempts
     * @author Shelton Bumhe
     */
    record OutboxMessage(
            UUID eventId,
            UUID aggregateId,
            UUID organizationId,
            String topic,
            String eventType,
            String partitionKey,
            String payload,
            Instant createdAt,
            int attempts) {}
}
