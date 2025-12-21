package com.anthem.pagw.subscription.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.subscription.model.Subscription;
import com.anthem.pagw.subscription.model.SubscriptionNotification;
import com.anthem.pagw.subscription.service.NotificationDeliveryService;
import com.anthem.pagw.subscription.service.SubscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SQS Listener for pagw-subscription-handler-queue.
 * 
 * Receives completed PA responses and delivers notifications
 * to all registered subscribers for the tenant.
 */
@Component
public class SubscriptionHandlerListener {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionHandlerListener.class);

    private final SubscriptionService subscriptionService;
    private final NotificationDeliveryService deliveryService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;

    public SubscriptionHandlerListener(
            SubscriptionService subscriptionService,
            NotificationDeliveryService deliveryService,
            S3Service s3Service,
            RequestTrackerService trackerService) {
        this.subscriptionService = subscriptionService;
        this.deliveryService = deliveryService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
    }

    @SqsListener(value = "${pagw.aws.sqs.subscription-handler-queue}")
    @Transactional
    public void handleMessage(
            String messageBody,
            @Header(value = "pagwId", required = false) String pagwIdHeader) {
        
        PagwMessage message = null;
        String pagwId = null;
        
        try {
            message = JsonUtils.fromJson(messageBody, PagwMessage.class);
            pagwId = message.getPagwId();
            String tenant = message.getTenant();
            
            log.info("Received subscription notification request: pagwId={}, tenant={}", 
                    pagwId, tenant);
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "NOTIFYING_SUBSCRIBERS", "subscription-handler");
            
            // Find all active subscriptions for this tenant
            List<Subscription> subscriptions = subscriptionService
                    .findActiveSubscriptionsForTenant(tenant);
            
            if (subscriptions.isEmpty()) {
                log.info("No active subscriptions for tenant: {}", tenant);
                trackerService.updateStatus(pagwId, "COMPLETED", "subscription-handler");
                return;
            }
            
            log.info("Found {} active subscriptions for tenant: {}", 
                    subscriptions.size(), tenant);
            
            // Fetch the response data from S3
            String responseKey = PagwProperties.S3Paths.fhirResponse(pagwId);
            String responseContent = null;
            try {
                responseContent = s3Service.getObject(message.getPayloadBucket(), responseKey);
            } catch (Exception e) {
                log.warn("Could not fetch full response from S3: {}", e.getMessage());
            }
            
            // Build notification from message
            SubscriptionNotification notification = buildNotification(message, responseContent);
            
            // Deliver to each subscription (async)
            for (Subscription subscription : subscriptions) {
                if (matchesFilter(subscription, notification)) {
                    deliveryService.deliverNotification(subscription, notification);
                } else {
                    log.debug("Subscription {} filtered out for pagwId={}", 
                            subscription.getId(), pagwId);
                }
            }
            
            trackerService.updateStatus(pagwId, "COMPLETED", "subscription-handler");
            log.info("Subscription notifications initiated: pagwId={}, subscriptions={}", 
                    pagwId, subscriptions.size());
            
        } catch (Exception e) {
            log.error("Error processing subscription notification: pagwId={}, error={}", 
                    pagwId, e.getMessage(), e);
            if (pagwId != null) {
                trackerService.updateStatus(pagwId, "SUBSCRIPTION_ERROR", "subscription-handler");
                trackerService.recordError(pagwId, "SUBSCRIPTION_DELIVERY_ERROR", e.getMessage());
            }
            throw new RuntimeException("Subscription handler error", e);
        }
    }

    /**
     * Build notification from PAGW message and response content.
     */
    private SubscriptionNotification buildNotification(PagwMessage message, String responseContent) {
        SubscriptionNotification notification = new SubscriptionNotification();
        notification.setPagwId(message.getPagwId());
        notification.setExternalReferenceId(message.getExternalReferenceId());
        notification.setType("event-notification");
        
        // Extract status from apiResponseStatus or metadata
        String apiStatus = message.getApiResponseStatus();
        if (apiStatus != null) {
            notification.setStatus(apiStatus);
            // Map API status to FHIR outcome
            notification.setOutcome(mapStatusToOutcome(apiStatus));
            notification.setDisposition(mapStatusToDisposition(apiStatus));
        } else {
            notification.setStatus("COMPLETED");
            notification.setOutcome("complete");
            notification.setDisposition("Prior authorization processed");
        }
        
        // Set resource reference (S3 path)
        notification.setResourceReference(String.format(
                "s3://%s/%s", 
                message.getPayloadBucket(),
                PagwProperties.S3Paths.fhirResponse(message.getPagwId())
        ));
        
        // Include full content if available
        if (responseContent != null) {
            notification.setResourceContent(responseContent);
        }
        
        return notification;
    }
    
    /**
     * Map API status to FHIR ClaimResponse outcome.
     */
    private String mapStatusToOutcome(String apiStatus) {
        if (apiStatus == null) return "complete";
        return switch (apiStatus.toUpperCase()) {
            case "ACCEPTED", "COMPLETE", "PROCESSED", "APPROVED" -> "complete";
            case "PENDING", "IN_PROGRESS", "QUEUED" -> "queued";
            case "PARTIAL", "PENDED" -> "partial";
            case "REJECTED", "DENIED", "ERROR", "FAILED" -> "error";
            default -> "complete";
        };
    }
    
    /**
     * Map API status to human-readable disposition.
     */
    private String mapStatusToDisposition(String apiStatus) {
        if (apiStatus == null) return "Prior authorization processed";
        return switch (apiStatus.toUpperCase()) {
            case "ACCEPTED", "COMPLETE", "APPROVED" -> "Prior authorization approved";
            case "PROCESSED" -> "Prior authorization processed";
            case "PENDING", "IN_PROGRESS" -> "Prior authorization pending review";
            case "QUEUED" -> "Prior authorization queued for processing";
            case "PARTIAL", "PENDED" -> "Prior authorization requires additional information";
            case "REJECTED", "DENIED" -> "Prior authorization denied";
            case "ERROR", "FAILED" -> "Prior authorization processing error";
            default -> "Prior authorization processed";
        };
    }

    /**
     * Check if notification matches subscription filter criteria.
     */
    private boolean matchesFilter(Subscription subscription, SubscriptionNotification notification) {
        String filter = subscription.getFilterCriteria();
        if (filter == null || filter.isEmpty()) {
            return true; // No filter = match all
        }
        
        // Simple filter parsing: "status=complete" or "outcome=queued,complete"
        for (String criterion : filter.split("&")) {
            String[] parts = criterion.split("=");
            if (parts.length != 2) continue;
            
            String field = parts[0].trim().toLowerCase();
            String[] allowedValues = parts[1].trim().toLowerCase().split(",");
            
            String actualValue = switch (field) {
                case "status" -> notification.getStatus();
                case "outcome" -> notification.getOutcome();
                default -> null;
            };
            
            if (actualValue != null) {
                boolean matches = false;
                for (String allowed : allowedValues) {
                    if (actualValue.equalsIgnoreCase(allowed.trim())) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) return false;
            }
        }
        
        return true;
    }
}
