package com.anthem.pagw.core.service;

import com.anthem.pagw.core.exception.PagwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service for audit logging PHI access and changes.
 * HIPAA-compliant audit trail.
 */
@Service
public class AuditService {
    
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Log an audit event.
     * 
     * @param event The audit event to log
     */
    @Transactional
    public void logEvent(AuditEvent event) {
        String sql = """
            INSERT INTO audit_log (
                id, event_timestamp, event_type, event_source,
                actor_id, actor_type, actor_ip,
                resource_type, resource_id, action_description,
                correlation_id, trace_id, request_path,
                phi_accessed, phi_fields_accessed, access_reason
            ) VALUES (
                ?::uuid, NOW(), ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?
            )
            """;
        
        jdbcTemplate.update(sql,
                UUID.randomUUID().toString(),
                event.eventType,
                event.eventSource,
                event.actorId,
                event.actorType,
                event.actorIp,
                event.resourceType,
                event.resourceId,
                event.actionDescription,
                event.correlationId,
                event.traceId,
                event.requestPath,
                event.phiAccessed,
                event.phiFieldsAccessed != null ? event.phiFieldsAccessed.toArray(new String[0]) : null,
                event.accessReason
        );
        
        log.debug("Audit event logged: type={}, resource={}/{}", 
                event.eventType, event.resourceType, event.resourceId);
    }
    
    /**
     * Log PHI access event.
     * 
     * @param pagwId The PAGW ID
     * @param actorId Actor accessing PHI
     * @param actorType Type of actor (USER, SERVICE, SYSTEM)
     * @param phiFields List of PHI fields accessed
     * @param reason Business justification
     * @param correlationId Correlation ID for tracing
     */
    public void logPhiAccess(String pagwId, String actorId, String actorType, 
                             java.util.List<String> phiFields, String reason, String correlationId) {
        AuditEvent event = new AuditEvent();
        event.eventType = "ACCESS";
        event.eventSource = "pagwcore";
        event.actorId = actorId;
        event.actorType = actorType;
        event.resourceType = "REQUEST";
        event.resourceId = pagwId;
        event.actionDescription = "PHI data accessed";
        event.correlationId = correlationId;
        event.phiAccessed = true;
        event.phiFieldsAccessed = phiFields;
        event.accessReason = reason;
        
        logEvent(event);
    }
    
    /**
     * Log request creation event.
     * 
     * @param pagwId The PAGW ID
     * @param actorId Actor creating request
     * @param correlationId Correlation ID
     */
    public void logRequestCreated(String pagwId, String actorId, String correlationId) {
        AuditEvent event = new AuditEvent();
        event.eventType = "CREATE";
        event.eventSource = "pasorchestrator";
        event.actorId = actorId;
        event.actorType = "SERVICE";
        event.resourceType = "REQUEST";
        event.resourceId = pagwId;
        event.actionDescription = "PAS request created";
        event.correlationId = correlationId;
        event.phiAccessed = true;
        event.accessReason = "Request processing";
        
        logEvent(event);
    }
    
    /**
     * Log request update event.
     * 
     * @param pagwId The PAGW ID
     * @param actorId Actor updating request
     * @param stage Current processing stage
     * @param correlationId Correlation ID
     */
    public void logRequestUpdated(String pagwId, String actorId, String stage, String correlationId) {
        AuditEvent event = new AuditEvent();
        event.eventType = "UPDATE";
        event.eventSource = stage.toLowerCase();
        event.actorId = actorId;
        event.actorType = "SERVICE";
        event.resourceType = "REQUEST";
        event.resourceId = pagwId;
        event.actionDescription = "Request updated at stage: " + stage;
        event.correlationId = correlationId;
        event.phiAccessed = true;
        event.accessReason = "Request processing";
        
        logEvent(event);
    }
    
    /**
     * Log prior authorization update event (modification of existing auth).
     * 
     * @param originalPagwId The original PAGW ID being updated
     * @param newPagwId The new PAGW ID for the update request
     * @param correlationId Correlation ID
     */
    public void logRequestUpdated(String originalPagwId, String newPagwId, String correlationId) {
        AuditEvent event = new AuditEvent();
        event.eventType = "MODIFY";
        event.eventSource = "pasorchestrator";
        event.actorId = "pasorchestrator";
        event.actorType = "SERVICE";
        event.resourceType = "REQUEST";
        event.resourceId = originalPagwId;
        event.actionDescription = "Prior authorization update submitted. New tracking ID: " + newPagwId;
        event.correlationId = correlationId;
        event.phiAccessed = true;
        event.accessReason = "Authorization modification";
        
        logEvent(event);
    }
    
    /**
     * Log prior authorization cancellation event.
     * 
     * @param originalPagwId The original PAGW ID being cancelled
     * @param cancelPagwId The PAGW ID for the cancel request
     * @param correlationId Correlation ID
     */
    public void logRequestCancelled(String originalPagwId, String cancelPagwId, String correlationId) {
        AuditEvent event = new AuditEvent();
        event.eventType = "CANCEL";
        event.eventSource = "pasorchestrator";
        event.actorId = "pasorchestrator";
        event.actorType = "SERVICE";
        event.resourceType = "REQUEST";
        event.resourceId = originalPagwId;
        event.actionDescription = "Prior authorization cancelled. Cancel request ID: " + cancelPagwId;
        event.correlationId = correlationId;
        event.phiAccessed = true;
        event.accessReason = "Authorization cancellation";
        
        logEvent(event);
    }
    
    /**
     * Log data export event (for compliance reporting).
     * 
     * @param pagwId The PAGW ID
     * @param actorId Actor exporting data
     * @param exportDestination Where data is being exported
     * @param correlationId Correlation ID
     */
    public void logDataExport(String pagwId, String actorId, String exportDestination, String correlationId) {
        AuditEvent event = new AuditEvent();
        event.eventType = "EXPORT";
        event.eventSource = "pagwcore";
        event.actorId = actorId;
        event.actorType = "SERVICE";
        event.resourceType = "REQUEST";
        event.resourceId = pagwId;
        event.actionDescription = "Data exported to: " + exportDestination;
        event.correlationId = correlationId;
        event.phiAccessed = true;
        event.accessReason = "External API submission";
        
        logEvent(event);
    }
    
    /**
     * Audit event data structure.
     */
    public static class AuditEvent {
        public String eventType;
        public String eventSource;
        public String actorId;
        public String actorType;
        public String actorIp;
        public String resourceType;
        public String resourceId;
        public String actionDescription;
        public String correlationId;
        public String traceId;
        public String requestPath;
        public boolean phiAccessed;
        public java.util.List<String> phiFieldsAccessed;
        public String accessReason;
    }
}
