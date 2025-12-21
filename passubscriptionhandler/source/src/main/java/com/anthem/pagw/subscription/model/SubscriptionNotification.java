package com.anthem.pagw.subscription.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a notification to be sent to a subscriber.
 * Based on FHIR R5 SubscriptionStatus pattern.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionNotification {
    
    /**
     * Unique notification ID
     */
    private String notificationId;
    
    /**
     * Subscription this notification is for
     */
    private String subscriptionId;
    
    /**
     * Type: heartbeat | event-notification | handshake
     */
    private String type;
    
    /**
     * PAGW request ID
     */
    private String pagwId;
    
    /**
     * External reference ID from the original request
     */
    private String externalReferenceId;
    
    /**
     * PA status: queued | complete | error | partial
     */
    private String status;
    
    /**
     * ClaimResponse outcome code
     */
    private String outcome;
    
    /**
     * Human-readable disposition
     */
    private String disposition;
    
    /**
     * Reference to the full ClaimResponse (if content-type = id-only)
     */
    private String resourceReference;
    
    /**
     * Full ClaimResponse bundle (if content-type = full-resource)
     */
    private String resourceContent;
    
    /**
     * Event timestamp
     */
    private Instant timestamp;
    
    /**
     * Delivery status: pending | delivered | failed
     */
    private String deliveryStatus;
    
    /**
     * Number of delivery attempts
     */
    private int deliveryAttempts;
    
    /**
     * Last delivery error
     */
    private String deliveryError;
}
