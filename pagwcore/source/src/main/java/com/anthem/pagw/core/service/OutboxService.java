package com.anthem.pagw.core.service;

import com.anthem.pagw.core.model.OutboxEntry;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbox Service for transactional outbox pattern.
 * Ensures atomic DB updates + event publishing.
 */
@Service
public class OutboxService {
    
    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    public OutboxService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Write an outbox entry within the current transaction.
     * Call this method within the same @Transactional boundary as your domain update.
     * 
     * @param destinationQueue Target SQS queue name
     * @param message The message to publish
     * @return The created OutboxEntry
     */
    @Transactional
    public OutboxEntry writeOutbox(String destinationQueue, PagwMessage message) {
        UUID id = UUID.randomUUID();
        String payload = JsonUtils.toJson(message);
        
        String sql = """
            INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, destination_queue, status, retry_count, created_at)
            VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, NOW())
            """;
        
        jdbcTemplate.update(sql, 
                id.toString(),
                "PagwMessage",
                message.getPagwId(),
                message.getStage(),
                payload,
                destinationQueue
        );
        
        log.info("Outbox entry created: id={}, destinationQueue={}, pagwId={}", id, destinationQueue, message.getPagwId());
        
        return OutboxEntry.builder()
                .id(id)
                .aggregateType("PagwMessage")
                .aggregateId(message.getPagwId())
                .eventType(message.getStage())
                .payload(payload)
                .destinationQueue(destinationQueue)
                .status(OutboxEntry.OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();
    }
    
    /**
     * Fetch unpublished outbox entries for processing.
     * Uses FOR UPDATE SKIP LOCKED for concurrency safety.
     * 
     * @param limit Maximum entries to fetch
     * @return List of unpublished entries
     */
    @Transactional
    public List<OutboxEntry> fetchUnpublished(int limit) {
        String sql = """
            SELECT id, aggregate_type, aggregate_id, event_type, payload, destination_queue, status, retry_count, last_error, created_at
            FROM outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            OutboxEntry entry = OutboxEntry.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .aggregateType(rs.getString("aggregate_type"))
                    .aggregateId(rs.getString("aggregate_id"))
                    .eventType(rs.getString("event_type"))
                    .payload(rs.getString("payload"))
                    .destinationQueue(rs.getString("destination_queue"))
                    .status(OutboxEntry.OutboxStatus.valueOf(rs.getString("status")))
                    .retryCount(rs.getInt("retry_count"))
                    .lastError(rs.getString("last_error"))
                    .build();
            
            Timestamp ts = rs.getTimestamp("created_at");
            if (ts != null) {
                entry.setCreatedAt(ts.toInstant());
            }
            
            return entry;
        }, limit);
    }
    
    /**
     * Mark an outbox entry as completed.
     * 
     * @param outboxId The outbox entry ID
     */
    @Transactional
    public void markCompleted(UUID outboxId) {
        String sql = "UPDATE outbox SET status = 'COMPLETED', processed_at = NOW() WHERE id = ?::uuid";
        int updated = jdbcTemplate.update(sql, outboxId.toString());
        
        if (updated > 0) {
            log.info("Outbox entry marked as completed: id={}", outboxId);
        } else {
            log.warn("Outbox entry not found for marking completed: id={}", outboxId);
        }
    }
    
    /**
     * Increment retry count and record error for failed publish.
     * 
     * @param outboxId The outbox entry ID
     * @param error The error message
     */
    @Transactional
    public void incrementRetry(UUID outboxId, String error) {
        String sql = "UPDATE outbox SET retry_count = retry_count + 1, last_error = ?, status = 'FAILED' WHERE id = ?::uuid";
        jdbcTemplate.update(sql, error, outboxId.toString());
        log.warn("Outbox entry retry incremented: id={}, error={}", outboxId, error);
    }
    
    /**
     * Get count of pending entries (for monitoring).
     * 
     * @return Number of pending outbox entries
     */
    public long getPendingCount() {
        String sql = "SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0;
    }
    
    /**
     * Get count of entries with retries exceeding threshold (for alerting).
     * 
     * @param maxRetries Retry threshold
     * @return Number of stuck entries
     */
    public long getStuckEntriesCount(int maxRetries) {
        String sql = "SELECT COUNT(*) FROM outbox WHERE status = 'FAILED' AND retry_count >= ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, maxRetries);
        return count != null ? count : 0;
    }
}
