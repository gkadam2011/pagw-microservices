package com.anthem.pagw.core.service;

import com.anthem.pagw.core.exception.PagwException;
import com.anthem.pagw.core.model.RequestTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Service for tracking request lifecycle through the PAGW pipeline.
 */
@Service
public class RequestTrackerService {
    
    private static final Logger log = LoggerFactory.getLogger(RequestTrackerService.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    public RequestTrackerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Create a new request tracker entry.
     * 
     * @param tracker The request tracker to create
     * @return The created tracker
     */
    @Transactional
    public RequestTracker create(RequestTracker tracker) {
        String sql = """
            INSERT INTO request_tracker (
                pagw_id, status, tenant, source_system, request_type,
                last_stage, workflow_id, raw_s3_bucket, raw_s3_key,
                contains_phi, idempotency_key, received_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (pagw_id) DO NOTHING
            """;
        
        int inserted = jdbcTemplate.update(sql,
                tracker.getPagwId(),
                tracker.getStatus(),
                tracker.getTenant(),
                tracker.getSourceSystem(),
                tracker.getRequestType(),
                tracker.getLastStage(),
                tracker.getWorkflowId(),
                tracker.getRawS3Bucket(),
                tracker.getRawS3Key(),
                tracker.isContainsPhi(),
                tracker.getIdempotencyKey(),
                tracker.getReceivedAt() != null ? Timestamp.from(tracker.getReceivedAt()) : null
        );
        
        if (inserted > 0) {
            log.info("Request tracker created: pagwId={}, status={}", tracker.getPagwId(), tracker.getStatus());
        } else {
            log.debug("Request tracker already exists: pagwId={}", tracker.getPagwId());
        }
        
        return tracker;
    }
    
    /**
     * Find a request tracker by pagwId.
     * 
     * @param pagwId The PAGW ID
     * @return Optional containing the tracker if found
     */
    public Optional<RequestTracker> findByPagwId(String pagwId) {
        String sql = """
            SELECT pagw_id, status, tenant, source_system, request_type,
                   last_stage, next_stage, workflow_id, last_error_code, last_error_msg,
                   retry_count, raw_s3_bucket, raw_s3_key, enriched_s3_bucket, enriched_s3_key,
                   final_s3_bucket, final_s3_key, contains_phi, idempotency_key,
                   received_at, completed_at, callback_sent_at, created_at, updated_at
            FROM request_tracker
            WHERE pagw_id = ?
            """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            RequestTracker tracker = new RequestTracker();
            tracker.setPagwId(rs.getString("pagw_id"));
            tracker.setStatus(rs.getString("status"));
            tracker.setTenant(rs.getString("tenant"));
            tracker.setSourceSystem(rs.getString("source_system"));
            tracker.setRequestType(rs.getString("request_type"));
            tracker.setLastStage(rs.getString("last_stage"));
            tracker.setNextStage(rs.getString("next_stage"));
            tracker.setWorkflowId(rs.getString("workflow_id"));
            tracker.setLastErrorCode(rs.getString("last_error_code"));
            tracker.setLastErrorMsg(rs.getString("last_error_msg"));
            tracker.setRetryCount(rs.getInt("retry_count"));
            tracker.setRawS3Bucket(rs.getString("raw_s3_bucket"));
            tracker.setRawS3Key(rs.getString("raw_s3_key"));
            tracker.setEnrichedS3Bucket(rs.getString("enriched_s3_bucket"));
            tracker.setEnrichedS3Key(rs.getString("enriched_s3_key"));
            tracker.setFinalS3Bucket(rs.getString("final_s3_bucket"));
            tracker.setFinalS3Key(rs.getString("final_s3_key"));
            tracker.setContainsPhi(rs.getBoolean("contains_phi"));
            tracker.setIdempotencyKey(rs.getString("idempotency_key"));
            
            Timestamp receivedAt = rs.getTimestamp("received_at");
            if (receivedAt != null) tracker.setReceivedAt(receivedAt.toInstant());
            
            Timestamp completedAt = rs.getTimestamp("completed_at");
            if (completedAt != null) tracker.setCompletedAt(completedAt.toInstant());
            
            Timestamp callbackSentAt = rs.getTimestamp("callback_sent_at");
            if (callbackSentAt != null) tracker.setCallbackSentAt(callbackSentAt.toInstant());
            
            Timestamp createdAt = rs.getTimestamp("created_at");
            if (createdAt != null) tracker.setCreatedAt(createdAt.toInstant());
            
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            if (updatedAt != null) tracker.setUpdatedAt(updatedAt.toInstant());
            
            return tracker;
        }, pagwId).stream().findFirst();
    }
    
    /**
     * Update the status and stage of a request.
     * 
     * @param pagwId The PAGW ID
     * @param status New status
     * @param lastStage Last completed stage
     * @param nextStage Next stage to process
     */
    @Transactional
    public void updateStatus(String pagwId, String status, String lastStage, String nextStage) {
        String sql = """
            UPDATE request_tracker
            SET status = ?, last_stage = ?, next_stage = ?, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        int updated = jdbcTemplate.update(sql, status, lastStage, nextStage, pagwId);
        
        if (updated > 0) {
            log.info("Request status updated: pagwId={}, status={}, stage={}", pagwId, status, lastStage);
        } else {
            log.warn("Request not found for status update: pagwId={}", pagwId);
        }
    }
    
    /**
     * Record an error for a request.
     * 
     * @param pagwId The PAGW ID
     * @param errorCode Error code
     * @param errorMsg Error message
     */
    @Transactional
    public void recordError(String pagwId, String errorCode, String errorMsg) {
        String sql = """
            UPDATE request_tracker
            SET status = 'ERROR', last_error_code = ?, last_error_msg = ?,
                retry_count = retry_count + 1, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, errorCode, errorMsg, pagwId);
        log.error("Request error recorded: pagwId={}, errorCode={}", pagwId, errorCode);
    }

    /**
     * Update error with stage (backwards compatibility alias for recordError).
     */
    @Transactional
    public void updateError(String pagwId, String errorCode, String errorMsg, String stage) {
        String sql = """
            UPDATE request_tracker
            SET status = 'ERROR', last_error_code = ?, last_error_msg = ?,
                last_stage = ?, retry_count = retry_count + 1, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, errorCode, errorMsg, stage, pagwId);
        log.error("Request error recorded: pagwId={}, errorCode={}, stage={}", pagwId, errorCode, stage);
    }
    
    /**
     * Mark a request as completed.
     * 
     * @param pagwId The PAGW ID
     * @param finalS3Bucket S3 bucket containing final response
     * @param finalS3Key S3 key for final response
     */
    @Transactional
    public void markCompleted(String pagwId, String finalS3Bucket, String finalS3Key) {
        String sql = """
            UPDATE request_tracker
            SET status = 'COMPLETED', final_s3_bucket = ?, final_s3_key = ?,
                completed_at = NOW(), updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, finalS3Bucket, finalS3Key, pagwId);
        log.info("Request marked completed: pagwId={}", pagwId);
    }
    
    /**
     * Update enriched data location.
     * 
     * @param pagwId The PAGW ID
     * @param enrichedS3Bucket S3 bucket
     * @param enrichedS3Key S3 key
     */
    @Transactional
    public void updateEnrichedLocation(String pagwId, String enrichedS3Bucket, String enrichedS3Key) {
        String sql = """
            UPDATE request_tracker
            SET enriched_s3_bucket = ?, enriched_s3_key = ?, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, enrichedS3Bucket, enrichedS3Key, pagwId);
        log.debug("Enriched location updated: pagwId={}", pagwId);
    }
    
    /**
     * Mark callback as sent.
     * 
     * @param pagwId The PAGW ID
     */
    @Transactional
    public void markCallbackSent(String pagwId) {
        String sql = """
            UPDATE request_tracker
            SET callback_sent_at = NOW(), updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, pagwId);
        log.info("Callback marked as sent: pagwId={}", pagwId);
    }

    /**
     * Update external reference ID (for payer system reference).
     * 
     * @param pagwId The PAGW ID
     * @param externalReferenceId Reference ID from payer system
     */
    @Transactional
    public void updateExternalReference(String pagwId, String externalReferenceId) {
        String sql = """
            UPDATE request_tracker
            SET external_reference_id = ?, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, externalReferenceId, pagwId);
        log.info("External reference updated: pagwId={}, externalRef={}", pagwId, externalReferenceId);
    }
    
    /**
     * Simple status update with stage tracking.
     * 
     * @param pagwId The PAGW ID
     * @param status New status
     * @param lastStage Last completed stage
     */
    @Transactional
    public void updateStatus(String pagwId, String status, String lastStage) {
        updateStatus(pagwId, status, lastStage, null);
    }
    
    /**
     * Update status with error information.
     * 
     * @param pagwId The PAGW ID
     * @param status New status
     * @param lastStage Last stage
     * @param errorCode Error code
     * @param errorMsg Error message
     */
    @Transactional
    public void updateStatus(String pagwId, String status, String lastStage, 
                            String errorCode, String errorMsg) {
        String sql = """
            UPDATE request_tracker
            SET status = ?, last_stage = ?, last_error_code = ?, last_error_msg = ?, 
                updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        int updated = jdbcTemplate.update(sql, status, lastStage, errorCode, errorMsg, pagwId);
        
        if (updated > 0) {
            log.info("Request status updated with error: pagwId={}, status={}, errorCode={}", 
                    pagwId, status, errorCode);
        }
    }
    
    /**
     * Update final status with S3 locations.
     * 
     * @param pagwId The PAGW ID
     * @param status Final status
     * @param lastStage Last stage
     * @param finalS3Bucket Final S3 bucket
     * @param finalS3Key Final S3 key
     */
    @Transactional
    public void updateFinalStatus(String pagwId, String status, String lastStage,
                                   String finalS3Bucket, String finalS3Key) {
        String sql = """
            UPDATE request_tracker
            SET status = ?, last_stage = ?, final_s3_bucket = ?, final_s3_key = ?,
                completed_at = NOW(), updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, status, lastStage, finalS3Bucket, finalS3Key, pagwId);
        log.info("Request final status updated: pagwId={}, status={}", pagwId, status);
    }
    
    /**
     * Find pagwId by external reference ID.
     * 
     * @param externalReferenceId External system reference ID
     * @return pagwId if found, null otherwise
     */
    public String findByExternalReference(String externalReferenceId) {
        String sql = """
            SELECT pagw_id FROM request_tracker
            WHERE external_reference_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """;
        
        try {
            return jdbcTemplate.queryForObject(sql, String.class, externalReferenceId);
        } catch (Exception e) {
            log.warn("No request found for external reference: {}", externalReferenceId);
            return null;
        }
    }
    
    /**
     * Find a request tracker by external request ID (payer reference).
     * 
     * @param externalId The external/payer reference ID
     * @return Optional containing the tracker if found
     */
    public Optional<RequestTracker> findByExternalId(String externalId) {
        String sql = """
            SELECT pagw_id, status, tenant, source_system, request_type,
                   last_stage, next_stage, workflow_id, last_error_code, last_error_msg,
                   retry_count, raw_s3_bucket, raw_s3_key, enriched_s3_bucket, enriched_s3_key,
                   final_s3_bucket, final_s3_key, contains_phi, idempotency_key,
                   external_request_id, received_at, completed_at, callback_sent_at, created_at, updated_at
            FROM request_tracker
            WHERE external_request_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """;
        
        return queryTracker(sql, externalId);
    }
    
    /**
     * Find a request tracker by idempotency key (Bundle.identifier).
     * 
     * @param idempotencyKey The idempotency key
     * @return Optional containing the tracker if found
     */
    public Optional<RequestTracker> findByIdempotencyKey(String idempotencyKey) {
        String sql = """
            SELECT pagw_id, status, tenant, source_system, request_type,
                   last_stage, next_stage, workflow_id, last_error_code, last_error_msg,
                   retry_count, raw_s3_bucket, raw_s3_key, enriched_s3_bucket, enriched_s3_key,
                   final_s3_bucket, final_s3_key, contains_phi, idempotency_key,
                   external_request_id, received_at, completed_at, callback_sent_at, created_at, updated_at
            FROM request_tracker
            WHERE idempotency_key = ?
            ORDER BY created_at DESC
            LIMIT 1
            """;
        
        return queryTracker(sql, idempotencyKey);
    }
    
    /**
     * Find a request tracker by patient and provider within a date range.
     * 
     * @param patientId Patient identifier
     * @param providerId Provider identifier
     * @param serviceDateFrom Service date range start (optional)
     * @param serviceDateTo Service date range end (optional)
     * @return Optional containing the tracker if found
     */
    public Optional<RequestTracker> findByPatientAndProvider(
            String patientId, 
            String providerId,
            java.time.LocalDate serviceDateFrom,
            java.time.LocalDate serviceDateTo) {
        
        StringBuilder sql = new StringBuilder("""
            SELECT pagw_id, status, tenant, source_system, request_type,
                   last_stage, next_stage, workflow_id, last_error_code, last_error_msg,
                   retry_count, raw_s3_bucket, raw_s3_key, enriched_s3_bucket, enriched_s3_key,
                   final_s3_bucket, final_s3_key, contains_phi, idempotency_key,
                   external_request_id, received_at, completed_at, callback_sent_at, created_at, updated_at
            FROM request_tracker
            WHERE member_id = ? AND provider_id = ?
            """);
        
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(patientId);
        params.add(providerId);
        
        if (serviceDateFrom != null) {
            sql.append(" AND DATE(received_at) >= ?");
            params.add(java.sql.Date.valueOf(serviceDateFrom));
        }
        if (serviceDateTo != null) {
            sql.append(" AND DATE(received_at) <= ?");
            params.add(java.sql.Date.valueOf(serviceDateTo));
        }
        
        sql.append(" ORDER BY created_at DESC LIMIT 1");
        
        return jdbcTemplate.query(sql.toString(), this::mapRowToTracker, params.toArray())
                .stream().findFirst();
    }
    
    /**
     * Update completed timestamp.
     * 
     * @param pagwId The PAGW ID
     * @param completedAt Completion timestamp
     */
    @Transactional
    public void updateCompletedAt(String pagwId, Instant completedAt) {
        String sql = """
            UPDATE request_tracker
            SET completed_at = ?, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, Timestamp.from(completedAt), pagwId);
        log.debug("Completed timestamp updated: pagwId={}", pagwId);
    }
    
    /**
     * Atomically mark a request as sync processed.
     * This prevents race conditions between sync timeout and async queueing.
     * 
     * @param pagwId The PAGW ID
     */
    @Transactional
    public void markSyncProcessed(String pagwId) {
        String sql = """
            UPDATE request_tracker
            SET sync_processed = true, sync_processed_at = NOW(), updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, pagwId);
        log.debug("Marked sync processed: pagwId={}", pagwId);
    }
    
    /**
     * Atomically try to mark a request for async queueing.
     * Returns true if successful (sync not yet processed), false otherwise.
     * Uses atomic UPDATE with condition to prevent race conditions.
     * 
     * @param pagwId The PAGW ID
     * @return true if async queueing should proceed, false if sync already processed
     */
    @Transactional
    public boolean tryMarkAsyncQueued(String pagwId) {
        String sql = """
            UPDATE request_tracker
            SET async_queued = true, async_queued_at = NOW(), updated_at = NOW()
            WHERE pagw_id = ? AND sync_processed = false AND async_queued = false
            """;
        
        int updated = jdbcTemplate.update(sql, pagwId);
        if (updated > 0) {
            log.debug("Marked for async queueing: pagwId={}", pagwId);
            return true;
        } else {
            log.debug("Async queueing skipped (already processed or queued): pagwId={}", pagwId);
            return false;
        }
    }
    
    // Helper method to query and map tracker
    private Optional<RequestTracker> queryTracker(String sql, String param) {
        return jdbcTemplate.query(sql, this::mapRowToTracker, param).stream().findFirst();
    }
    
    // Row mapper for RequestTracker
    private RequestTracker mapRowToTracker(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        RequestTracker tracker = new RequestTracker();
        tracker.setPagwId(rs.getString("pagw_id"));
        tracker.setStatus(rs.getString("status"));
        tracker.setTenant(rs.getString("tenant"));
        tracker.setSourceSystem(rs.getString("source_system"));
        tracker.setRequestType(rs.getString("request_type"));
        tracker.setLastStage(rs.getString("last_stage"));
        tracker.setNextStage(rs.getString("next_stage"));
        tracker.setWorkflowId(rs.getString("workflow_id"));
        tracker.setLastErrorCode(rs.getString("last_error_code"));
        tracker.setLastErrorMsg(rs.getString("last_error_msg"));
        tracker.setRetryCount(rs.getInt("retry_count"));
        tracker.setRawS3Bucket(rs.getString("raw_s3_bucket"));
        tracker.setRawS3Key(rs.getString("raw_s3_key"));
        tracker.setEnrichedS3Bucket(rs.getString("enriched_s3_bucket"));
        tracker.setEnrichedS3Key(rs.getString("enriched_s3_key"));
        tracker.setFinalS3Bucket(rs.getString("final_s3_bucket"));
        tracker.setFinalS3Key(rs.getString("final_s3_key"));
        tracker.setContainsPhi(rs.getBoolean("contains_phi"));
        tracker.setIdempotencyKey(rs.getString("idempotency_key"));
        tracker.setExternalRequestId(rs.getString("external_request_id"));
        
        Timestamp receivedAt = rs.getTimestamp("received_at");
        if (receivedAt != null) tracker.setReceivedAt(receivedAt.toInstant());
        
        Timestamp completedAt = rs.getTimestamp("completed_at");
        if (completedAt != null) tracker.setCompletedAt(completedAt.toInstant());
        
        Timestamp callbackSentAt = rs.getTimestamp("callback_sent_at");
        if (callbackSentAt != null) tracker.setCallbackSentAt(callbackSentAt.toInstant());
        
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) tracker.setCreatedAt(createdAt.toInstant());
        
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) tracker.setUpdatedAt(updatedAt.toInstant());
        
        return tracker;
    }
    
    /**
     * Update extracted FHIR data fields (diagnosis codes).
     * Called after FHIR extraction in parser service.
     * 
     * @param pagwId The PAGW ID
     * @param diagnosisCodesJson JSON array of diagnosis codes
     */
    @Transactional
    public void updateDiagnosisCodes(String pagwId, String diagnosisCodesJson) {
        String sql = """
            UPDATE request_tracker
            SET diagnosis_codes = ?::jsonb, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, diagnosisCodesJson, pagwId);
        log.debug("Diagnosis codes updated: pagwId={}", pagwId);
    }
    
    /**
     * Update extracted FHIR metadata fields (patient_member_id, provider_npi).
     * Called after FHIR extraction in parser service to populate existing columns.
     * 
     * @param pagwId The PAGW ID
     * @param patientMemberId Patient member ID from FHIR Patient resource
     * @param providerNpi Provider NPI from FHIR Practitioner resource
     */
    @Transactional
    public void updateFhirMetadata(String pagwId, String patientMemberId, String providerNpi) {
        String sql = """
            UPDATE request_tracker
            SET patient_member_id = ?, provider_npi = ?, updated_at = NOW()
            WHERE pagw_id = ?
            """;
        
        jdbcTemplate.update(sql, patientMemberId, providerNpi, pagwId);
        log.debug("FHIR metadata updated: pagwId={}, memberId={}, npi={}", 
            pagwId, patientMemberId, providerNpi);
    }
    
    /**
     * Get the underlying JdbcTemplate for direct SQL execution.
     * Used by microservices for custom queries not yet in the core service.
     * 
     * @return The JdbcTemplate
     */
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }
}
