package com.anthem.pagw.orchestrator.config;

import com.anthem.pagw.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Local development AuditService that only logs to console.
 * 
 * Bypasses database writes which require matching schema.
 * For local testing only.
 */
@Service
@Primary
@Profile({"local", "docker"})
public class LocalAuditService extends AuditService {
    
    private static final Logger log = LoggerFactory.getLogger(LocalAuditService.class);
    
    public LocalAuditService(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
    
    @Override
    public void logEvent(AuditEvent event) {
        log.info("[AUDIT] type={}, source={}, resourceType={}, resourceId={}, action={}", 
                event.eventType, event.eventSource, event.resourceType, 
                event.resourceId, event.actionDescription);
    }
    
    @Override
    public void logPhiAccess(String pagwId, String actorId, String actorType, 
                             List<String> phiFields, String reason, String correlationId) {
        log.info("[PHI_ACCESS] pagwId={}, actor={}/{}, fields={}, reason={}", 
                pagwId, actorType, actorId, phiFields, reason);
    }
    
    @Override
    public void logRequestCreated(String pagwId, String actorId, String correlationId) {
        log.info("[REQUEST_CREATED] pagwId={}, actor={}, correlationId={}", 
                pagwId, actorId, correlationId);
    }
    
    @Override
    public void logRequestUpdated(String pagwId, String actorId, String stage, String correlationId) {
        log.info("[REQUEST_UPDATED] pagwId={}, actor={}, stage={}, correlationId={}", 
                pagwId, actorId, stage, correlationId);
    }
    
    @Override
    public void logRequestUpdated(String originalPagwId, String newPagwId, String correlationId) {
        log.info("[REQUEST_UPDATED] originalPagwId={}, newPagwId={}, correlationId={}", 
                originalPagwId, newPagwId, correlationId);
    }
    
    @Override
    public void logRequestCancelled(String originalPagwId, String cancelPagwId, String correlationId) {
        log.info("[REQUEST_CANCELLED] originalPagwId={}, cancelPagwId={}, correlationId={}", 
                originalPagwId, cancelPagwId, correlationId);
    }
    
    @Override
    public void logDataExport(String pagwId, String actorId, String exportDestination, String correlationId) {
        log.info("[DATA_EXPORT] pagwId={}, actor={}, destination={}, correlationId={}", 
                pagwId, actorId, exportDestination, correlationId);
    }
}
