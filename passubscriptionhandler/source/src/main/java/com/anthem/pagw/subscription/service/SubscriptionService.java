package com.anthem.pagw.subscription.service;

import com.anthem.pagw.subscription.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing subscription registrations.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Find all active subscriptions for a tenant.
     */
    public List<Subscription> findActiveSubscriptionsForTenant(String tenantId) {
        String sql = """
            SELECT id, tenant_id, status, channel_type, endpoint, content_type, 
                   headers, filter_criteria, expires_at, created_at, 
                   last_delivered_at, failure_count, last_error
            FROM pagw.subscriptions 
            WHERE tenant_id = ? AND status = 'active' 
            AND (expires_at IS NULL OR expires_at > NOW())
            """;
        
        return jdbcTemplate.query(sql, subscriptionRowMapper(), tenantId);
    }

    /**
     * Find subscription by ID.
     */
    public Optional<Subscription> findById(String subscriptionId) {
        String sql = """
            SELECT id, tenant_id, status, channel_type, endpoint, content_type, 
                   headers, filter_criteria, expires_at, created_at, 
                   last_delivered_at, failure_count, last_error
            FROM pagw.subscriptions 
            WHERE id = ?
            """;
        
        List<Subscription> results = jdbcTemplate.query(sql, subscriptionRowMapper(), subscriptionId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Create a new subscription.
     */
    @Transactional
    public Subscription createSubscription(Subscription subscription) {
        String id = UUID.randomUUID().toString();
        subscription.setId(id);
        subscription.setStatus("active");
        subscription.setCreatedAt(Instant.now());
        
        String sql = """
            INSERT INTO pagw.subscriptions 
            (id, tenant_id, status, channel_type, endpoint, content_type, 
             headers, filter_criteria, expires_at, created_at, failure_count)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 0)
            """;
        
        jdbcTemplate.update(sql,
                subscription.getId(),
                subscription.getTenantId(),
                subscription.getStatus(),
                subscription.getChannelType(),
                subscription.getEndpoint(),
                subscription.getContentType(),
                subscription.getHeaders() != null ? 
                    com.anthem.pagw.core.util.JsonUtils.toJson(subscription.getHeaders()) : null,
                subscription.getFilterCriteria(),
                subscription.getExpiresAt() != null ? 
                    Timestamp.from(subscription.getExpiresAt()) : null,
                Timestamp.from(subscription.getCreatedAt())
        );
        
        log.info("Created subscription: id={}, tenant={}, endpoint={}", 
                id, subscription.getTenantId(), subscription.getEndpoint());
        
        return subscription;
    }

    /**
     * Update subscription status after delivery attempt.
     */
    @Transactional
    public void updateDeliveryStatus(String subscriptionId, boolean success, String errorMessage) {
        if (success) {
            String sql = """
                UPDATE pagw.subscriptions 
                SET last_delivered_at = NOW(), failure_count = 0, last_error = NULL
                WHERE id = ?
                """;
            jdbcTemplate.update(sql, subscriptionId);
        } else {
            String sql = """
                UPDATE pagw.subscriptions 
                SET failure_count = failure_count + 1, last_error = ?,
                    status = CASE WHEN failure_count >= 5 THEN 'error' ELSE status END
                WHERE id = ?
                """;
            jdbcTemplate.update(sql, errorMessage, subscriptionId);
        }
    }

    /**
     * Deactivate a subscription.
     */
    @Transactional
    public void deactivateSubscription(String subscriptionId) {
        String sql = "UPDATE pagw.subscriptions SET status = 'off' WHERE id = ?";
        jdbcTemplate.update(sql, subscriptionId);
        log.info("Deactivated subscription: id={}", subscriptionId);
    }

    private RowMapper<Subscription> subscriptionRowMapper() {
        return (rs, rowNum) -> {
            Subscription sub = new Subscription();
            sub.setId(rs.getString("id"));
            sub.setTenantId(rs.getString("tenant_id"));
            sub.setStatus(rs.getString("status"));
            sub.setChannelType(rs.getString("channel_type"));
            sub.setEndpoint(rs.getString("endpoint"));
            sub.setContentType(rs.getString("content_type"));
            
            String headersJson = rs.getString("headers");
            if (headersJson != null) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    sub.setHeaders(mapper.readValue(headersJson, 
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {}));
                } catch (Exception e) {
                    // Ignore parse errors for headers
                }
            }
            
            sub.setFilterCriteria(rs.getString("filter_criteria"));
            
            Timestamp expiresAt = rs.getTimestamp("expires_at");
            if (expiresAt != null) sub.setExpiresAt(expiresAt.toInstant());
            
            Timestamp createdAt = rs.getTimestamp("created_at");
            if (createdAt != null) sub.setCreatedAt(createdAt.toInstant());
            
            Timestamp lastDeliveredAt = rs.getTimestamp("last_delivered_at");
            if (lastDeliveredAt != null) sub.setLastDeliveredAt(lastDeliveredAt.toInstant());
            
            sub.setFailureCount(rs.getInt("failure_count"));
            sub.setLastError(rs.getString("last_error"));
            
            return sub;
        };
    }
}
