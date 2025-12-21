package com.anthem.pagw.response.config;

import com.anthem.pagw.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Local/Docker implementation of AuditService that logs to console
 * instead of writing to database.
 */
@Service
@Primary
@Profile({"local", "docker"})
public class LocalAuditService extends AuditService {

    private static final Logger log = LoggerFactory.getLogger(LocalAuditService.class);

    public LocalAuditService() {
        super(null); // No JdbcTemplate needed for local
    }

    @Override
    public void logEvent(AuditEvent event) {
        log.info("[AUDIT] type={}, resource={}/{}, actor={}", 
                event.eventType, event.resourceType, event.resourceId, event.actorId);
    }
}
