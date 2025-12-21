package com.anthem.pagw.subscription.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a subscription registration for PA notifications.
 * Based on FHIR R5 Subscription resource pattern.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {
    
    /**
     * Unique identifier for this subscription
     */
    private String id;
    
    /**
     * Tenant/client ID that owns this subscription
     */
    private String tenantId;
    
    /**
     * Status: requested | active | error | off
     */
    private String status;
    
    /**
     * Type of notification channel: rest-hook | websocket | email
     */
    private String channelType;
    
    /**
     * Endpoint URL for webhook delivery
     */
    private String endpoint;
    
    /**
     * Content type for notifications: id-only | full-resource
     */
    private String contentType;
    
    /**
     * Headers to include in webhook calls
     */
    private Map<String, String> headers;
    
    /**
     * Filter criteria - which events to subscribe to
     * e.g., "status=complete" or "outcome=queued,complete"
     */
    private String filterCriteria;
    
    /**
     * Subscription expiration time
     */
    private Instant expiresAt;
    
    /**
     * When subscription was created
     */
    private Instant createdAt;
    
    /**
     * Last successful notification delivery
     */
    private Instant lastDeliveredAt;
    
    /**
     * Number of failed delivery attempts
     */
    private int failureCount;
    
    /**
     * Last error message if delivery failed
     */
    private String lastError;
}
