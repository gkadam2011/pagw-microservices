package com.anthem.pagw.parser.config;

import com.anthem.pagw.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Local development AuditService that only logs to console.
 * Bypasses database writes which require matching schema.
 */
@Service
@Primary
@Profile({"local", "docker"})
public class LocalAuditService extends AuditService {
    
    private static final Logger log = LoggerFactory.getLogger(LocalAuditService.class);
    
    @Autowired
    public LocalAuditService(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }
    
    @Override
    public void logEvent(AuditEvent event) {
        log.info("[AUDIT] type={}, source={}, resourceType={}, resourceId={}", 
                event.eventType, event.eventSource, event.resourceType, event.resourceId);
    }
    
    @Override
    public void logPhiAccess(String pagwId, String actorId, String actorType, 
                             List<String> phiFields, String reason, String correlationId) {
        log.info("[PHI_ACCESS] pagwId={}, actor={}/{}", pagwId, actorType, actorId);
    }
    
    @Override
    public void logRequestCreated(String pagwId, String actorId, String correlationId) {
        log.info("[REQUEST_CREATED] pagwId={}, actor={}", pagwId, actorId);
    }
    
    @Override
    public void logRequestUpdated(String pagwId, String actorId, String stage, String correlationId) {
        log.info("[REQUEST_UPDATED] pagwId={}, stage={}", pagwId, stage);
    }
    
    @Override
    public void logRequestUpdated(String originalPagwId, String newPagwId, String correlationId) {
        log.info("[REQUEST_UPDATED] original={}, new={}", originalPagwId, newPagwId);
    }
    
    @Override
    public void logRequestCancelled(String originalPagwId, String cancelPagwId, String correlationId) {
        log.info("[REQUEST_CANCELLED] original={}, cancel={}", originalPagwId, cancelPagwId);
    }
    
    @Override
    public void logDataExport(String pagwId, String actorId, String exportDestination, String correlationId) {
        log.info("[DATA_EXPORT] pagwId={}, destination={}", pagwId, exportDestination);
    }
}
