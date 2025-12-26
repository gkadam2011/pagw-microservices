package com.anthem.pagw.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbox entry for reliable event publishing.
 * Used by OutboxPublisher to guarantee exactly-once delivery to SQS.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutboxEntry {

    private UUID id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payload;
    private String destinationQueue;
    
    private OutboxStatus status;
    private int retryCount;
    private String lastError;
    
    private Instant createdAt;
    private Instant processedAt;

    public enum OutboxStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        DEAD_LETTER
    }

    public static OutboxEntry create(String aggregateType, String aggregateId, 
                                      String eventType, String payload, String destinationQueue) {
        return OutboxEntry.builder()
                .id(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payload)
                .destinationQueue(destinationQueue)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();
    }
}
