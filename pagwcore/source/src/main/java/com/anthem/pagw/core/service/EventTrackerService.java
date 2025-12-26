package com.anthem.pagw.core.service;

import com.anthem.pagw.core.exception.PagwException;
import com.anthem.pagw.core.model.EventTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for tracking events through the PAGW pipeline stages.
 * Events provide fine-grained observability into request processing.
 * 
 * <p>Usage pattern:
 * <pre>
 * // Stage start
 * eventTrackerService.logStageStart(pagwId, tenant, "PARSING", "PARSE_START");
 * 
 * // Stage completion
 * eventTrackerService.logStageComplete(pagwId, tenant, "PARSING", "PARSE_OK", durationMs);
 * 
 * // Stage error
 * eventTrackerService.logStageError(pagwId, tenant, "PARSING", "PARSE_FAIL", "INVALID_BUNDLE", errorMsg);
 * </pre>
 */
@Service
public class EventTrackerService {
    
    private static final Logger log = LoggerFactory.getLogger(EventTrackerService.class);
    private static final String DEFAULT_TENANT = "UNKNOWN";
    
    private final JdbcTemplate jdbcTemplate;
    
    public EventTrackerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Ensures tenant is never null to prevent NOT NULL constraint violations.
     */
    private String safeTenant(String tenant) {
        return (tenant != null && !tenant.isBlank()) ? tenant : DEFAULT_TENANT;
    }
    
    /**
     * Log a stage start event.
     * 
     * @param pagwId The PAGW request ID
     * @param tenant The tenant identifier
     * @param stage The pipeline stage
     * @param eventType The event type (e.g., PARSE_START, VAL_START)
     * @return The sequence number assigned to this event
     */
    @Transactional
    public long logStageStart(String pagwId, String tenant, String stage, String eventType) {
        return logStageStart(pagwId, tenant, stage, eventType, null);
    }
    
    /**
     * Log a stage start event with metadata.
     * 
     * @param pagwId The PAGW request ID
     * @param tenant The tenant identifier
     * @param stage The pipeline stage
     * @param eventType The event type
     * @param metadata JSON metadata (no PHI)
     * @return The sequence number assigned to this event
     */
    @Transactional
    public long logStageStart(String pagwId, String tenant, String stage, String eventType, String metadata) {
        long sequenceNo = getNextSequenceNo(pagwId);
        
        String sql = """
            INSERT INTO pagw.event_tracker (
                tenant, pagw_id, stage, event_type, status,
                sequence_no, attempt, retryable,
                started_at, metadata, created_at
            ) VALUES (?, ?, ?, ?, 'STARTED', ?, 0, FALSE, NOW(), ?::jsonb, NOW())
            """;
        
        jdbcTemplate.update(sql, safeTenant(tenant), pagwId, stage, eventType, sequenceNo, metadata);
        
        log.debug("Event logged: pagwId={}, stage={}, eventType={}, sequenceNo={}", 
                pagwId, stage, eventType, sequenceNo);
        
        return sequenceNo;
    }
    
    /**
     * Log a stage completion event.
     * 
     * @param pagwId The PAGW request ID
     * @param tenant The tenant identifier
     * @param stage The pipeline stage
     * @param eventType The event type (e.g., PARSE_OK, VAL_OK)
     * @param durationMs The duration in milliseconds
     * @return The sequence number assigned to this event
     */
    @Transactional
    public long logStageComplete(String pagwId, String tenant, String stage, String eventType, long durationMs) {
        return logStageComplete(pagwId, tenant, stage, eventType, durationMs, null);
    }
    
    /**
     * Log a stage completion event with metadata.
     * 
     * @param pagwId The PAGW request ID
     * @param tenant The tenant identifier
     * @param stage The pipeline stage
     * @param eventType The event type
     * @param durationMs The duration in milliseconds
     * @param metadata JSON metadata (no PHI)
     * @return The sequence number assigned to this event
     */
    @Transactional
    public long logStageComplete(String pagwId, String tenant, String stage, String eventType, 
                                  long durationMs, String metadata) {
        long sequenceNo = getNextSequenceNo(pagwId);
        
        String sql = """
            INSERT INTO pagw.event_tracker (
                tenant, pagw_id, stage, event_type, status,
                sequence_no, attempt, retryable,
                duration_ms, completed_at, metadata, created_at
            ) VALUES (?, ?, ?, ?, 'SUCCESS', ?, 0, FALSE, ?, NOW(), ?::jsonb, NOW())
            """;
        
        jdbcTemplate.update(sql, safeTenant(tenant), pagwId, stage, eventType, sequenceNo, durationMs, metadata);
        
        log.info("Stage completed: pagwId={}, stage={}, eventType={}, duration={}ms, sequenceNo={}", 
                pagwId, stage, eventType, durationMs, sequenceNo);
        
        return sequenceNo;
    }
    
    /**
     * Log a stage error event.
     * 
     * @param pagwId The PAGW request ID
     * @param tenant The tenant identifier
     * @param stage The pipeline stage
     * @param eventType The event type (e.g., PARSE_FAIL, VAL_FAIL)
     * @param errorCode The error code
     * @param errorMessage The error message
     * @return The sequence number assigned to this event
     */
    @Transactional
    public long logStageError(String pagwId, String tenant, String stage, String eventType, 
                              String errorCode, String errorMessage) {
        return logStageError(pagwId, tenant, stage, eventType, errorCode, errorMessage, false, null);
    }
    
    /**
     * Log a stage error event with retry information.
     * 
     * @param pagwId The PAGW request ID
     * @param tenant The tenant identifier
     * @param stage The pipeline stage
     * @param eventType The event type
     * @param errorCode The error code
     * @param errorMessage The error message
     * @param retryable Whether this error is retryable
     * @param nextRetryAt When to retry (if retryable)
     * @return The sequence number assigned to this event
     */
    @Transactional
    public long logStageError(String pagwId, String tenant, String stage, String eventType, 
                              String errorCode, String errorMessage, boolean retryable, Instant nextRetryAt) {
        long sequenceNo = getNextSequenceNo(pagwId);
        int attempt = getCurrentAttempt(pagwId, stage, eventType);
        
        String sql = """
            INSERT INTO pagw.event_tracker (
                tenant, pagw_id, stage, event_type, status,
                sequence_no, attempt, retryable, next_retry_at,
                error_code, error_message, completed_at, created_at
            ) VALUES (?, ?, ?, ?, 'FAILURE', ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;
        
        jdbcTemplate.update(sql, safeTenant(tenant), pagwId, stage, eventType, sequenceNo, attempt, retryable,
                nextRetryAt != null ? Timestamp.from(nextRetryAt) : null, errorCode, errorMessage);
        
        log.error("Stage failed: pagwId={}, stage={}, eventType={}, errorCode={}, retryable={}, attempt={}, sequenceNo={}", 
                pagwId, stage, eventType, errorCode, retryable, attempt, sequenceNo);
        
        return sequenceNo;
    }
    
    /**
     * Log a retry attempt.
     * 
     * @param pagwId The PAGW request ID
     * @param tenant The tenant identifier
     * @param stage The pipeline stage
     * @param eventType The event type
     * @param attempt The retry attempt number
     * @return The sequence number assigned to this event
     */
    @Transactional
    public long logRetryAttempt(String pagwId, String tenant, String stage, String eventType, int attempt) {
        long sequenceNo = getNextSequenceNo(pagwId);
        
        String sql = """
            INSERT INTO pagw.event_tracker (
                tenant, pagw_id, stage, event_type, status,
                sequence_no, attempt, retryable,
                started_at, created_at
            ) VALUES (?, ?, ?, ?, 'RETRY', ?, ?, TRUE, NOW(), NOW())
            """;
        
        jdbcTemplate.update(sql, safeTenant(tenant), pagwId, stage, eventType, sequenceNo, attempt);
        
        log.info("Retry attempt: pagwId={}, stage={}, eventType={}, attempt={}, sequenceNo={}", 
                pagwId, stage, eventType, attempt, sequenceNo);
        
        return sequenceNo;
    }
    
    /**
     * Get the timeline of events for a request.
     * 
     * @param pagwId The PAGW request ID
     * @return List of events in sequence order
     */
    public List<EventTracker> getTimeline(String pagwId) {
        String sql = """
            SELECT id, tenant, pagw_id, stage, event_type, status, execution_status,
                   sequence_no, attempt, retryable, next_retry_at,
                   duration_ms, error_code, error_message, worker_id,
                   metadata, started_at, completed_at, created_at
            FROM pagw.event_tracker
            WHERE pagw_id = ?
            ORDER BY sequence_no ASC
            """;
        
        return jdbcTemplate.query(sql, this::mapEventTracker, pagwId);
    }
    
    /**
     * Get failed retryable events for a tenant within a time window.
     * 
     * @param tenant The tenant identifier
     * @param minutesAgo How many minutes to look back
     * @return List of failed retryable events
     */
    public List<EventTracker> getFailedRetryableEvents(String tenant, int minutesAgo) {
        String sql = """
            SELECT id, tenant, pagw_id, stage, event_type, status, execution_status,
                   sequence_no, attempt, retryable, next_retry_at,
                   duration_ms, error_code, error_message, worker_id,
                   metadata, started_at, completed_at, created_at
            FROM pagw.event_tracker
            WHERE tenant = ?
              AND retryable = TRUE
              AND status = 'FAILURE'
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
              AND created_at >= NOW() - INTERVAL '%d minutes'
            ORDER BY created_at ASC
            """.formatted(minutesAgo);
        
        return jdbcTemplate.query(sql, this::mapEventTracker, tenant);
    }
    
    /**
     * Update worker ID for an event (for tracking which pod/lambda processed it).
     * 
     * @param eventId The event ID
     * @param workerId The worker identifier (pod name, lambda request ID, etc.)
     */
    @Transactional
    public void updateWorkerId(long eventId, String workerId) {
        String sql = "UPDATE pagw.event_tracker SET worker_id = ? WHERE id = ?";
        jdbcTemplate.update(sql, workerId, eventId);
    }
    
    /**
     * Get the next sequence number for a request.
     * This ensures monotonic ordering of events.
     * 
     * @param pagwId The PAGW request ID
     * @return The next sequence number
     */
    private long getNextSequenceNo(String pagwId) {
        String sql = """
            SELECT COALESCE(MAX(sequence_no), 0) + 1
            FROM pagw.event_tracker
            WHERE pagw_id = ?
            """;
        
        Long nextSeq = jdbcTemplate.queryForObject(sql, Long.class, pagwId);
        return nextSeq != null ? nextSeq : 1L;
    }
    
    /**
     * Get the current attempt count for a specific stage/event.
     * 
     * @param pagwId The PAGW request ID
     * @param stage The pipeline stage
     * @param eventType The event type
     * @return The current attempt count
     */
    private int getCurrentAttempt(String pagwId, String stage, String eventType) {
        String sql = """
            SELECT COALESCE(MAX(attempt), 0) + 1
            FROM pagw.event_tracker
            WHERE pagw_id = ?
              AND stage = ?
              AND event_type = ?
            """;
        
        Integer attempt = jdbcTemplate.queryForObject(sql, Integer.class, pagwId, stage, eventType);
        return attempt != null ? attempt : 0;
    }
    
    /**
     * Map ResultSet to EventTracker model.
     */
    private EventTracker mapEventTracker(ResultSet rs, int rowNum) throws SQLException {
        EventTracker event = new EventTracker();
        event.setId(rs.getLong("id"));
        event.setTenant(rs.getString("tenant"));
        event.setPagwId(rs.getString("pagw_id"));
        event.setStage(rs.getString("stage"));
        event.setEventType(rs.getString("event_type"));
        event.setStatus(rs.getString("status"));
        event.setExecutionStatus(rs.getString("execution_status"));
        event.setSequenceNo(rs.getLong("sequence_no"));
        event.setAttempt(rs.getInt("attempt"));
        event.setRetryable(rs.getBoolean("retryable"));
        
        Timestamp nextRetryAt = rs.getTimestamp("next_retry_at");
        if (nextRetryAt != null) {
            event.setNextRetryAt(nextRetryAt.toInstant());
        }
        
        event.setDurationMs(rs.getInt("duration_ms"));
        event.setErrorCode(rs.getString("error_code"));
        event.setErrorMessage(rs.getString("error_message"));
        event.setWorkerId(rs.getString("worker_id"));
        event.setMetadata(rs.getString("metadata"));
        
        Timestamp startedAt = rs.getTimestamp("started_at");
        if (startedAt != null) {
            event.setStartedAt(startedAt.toInstant());
        }
        
        Timestamp completedAt = rs.getTimestamp("completed_at");
        if (completedAt != null) {
            event.setCompletedAt(completedAt.toInstant());
        }
        
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            event.setCreatedAt(createdAt.toInstant());
        }
        
        return event;
    }
}
