package com.anthem.pagw.subscription.service;

import com.anthem.pagw.subscription.model.Subscription;
import com.anthem.pagw.subscription.model.SubscriptionNotification;
import com.anthem.pagw.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Service for delivering notifications to subscriber endpoints.
 * Implements retry logic and error handling for webhook delivery.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int MAX_RETRIES = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webhookClient;
    private final SubscriptionService subscriptionService;

    public NotificationDeliveryService(WebClient webhookClient, SubscriptionService subscriptionService) {
        this.webhookClient = webhookClient;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Deliver notification to a subscription endpoint asynchronously.
     */
    @Async
    public void deliverNotification(Subscription subscription, SubscriptionNotification notification) {
        log.info("Delivering notification: subscriptionId={}, pagwId={}, endpoint={}", 
                subscription.getId(), notification.getPagwId(), subscription.getEndpoint());

        notification.setNotificationId(UUID.randomUUID().toString());
        notification.setSubscriptionId(subscription.getId());
        notification.setTimestamp(Instant.now());
        notification.setDeliveryStatus("pending");

        int attempt = 0;
        boolean delivered = false;
        String lastError = null;

        while (attempt < MAX_RETRIES && !delivered) {
            attempt++;
            notification.setDeliveryAttempts(attempt);
            
            try {
                delivered = attemptDelivery(subscription, notification);
                if (delivered) {
                    notification.setDeliveryStatus("delivered");
                    log.info("Notification delivered successfully: subscriptionId={}, pagwId={}, attempts={}", 
                            subscription.getId(), notification.getPagwId(), attempt);
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Notification delivery attempt {} failed: subscriptionId={}, error={}", 
                        attempt, subscription.getId(), lastError);
                
                if (attempt < MAX_RETRIES) {
                    // Exponential backoff: 1s, 2s, 4s
                    try {
                        Thread.sleep((long) Math.pow(2, attempt - 1) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!delivered) {
            notification.setDeliveryStatus("failed");
            notification.setDeliveryError(lastError);
            log.error("Notification delivery failed after {} attempts: subscriptionId={}, pagwId={}", 
                    MAX_RETRIES, subscription.getId(), notification.getPagwId());
        }

        // Update subscription delivery status
        subscriptionService.updateDeliveryStatus(subscription.getId(), delivered, lastError);
    }

    /**
     * Attempt a single delivery to the endpoint.
     */
    private boolean attemptDelivery(Subscription subscription, SubscriptionNotification notification) {
        String payload = buildNotificationPayload(subscription, notification);

        WebClient.RequestBodySpec request = webhookClient.post()
                .uri(subscription.getEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Subscription-Id", subscription.getId())
                .header("X-Notification-Id", notification.getNotificationId())
                .header("X-PAGW-Id", notification.getPagwId());

        // Add custom headers from subscription
        if (subscription.getHeaders() != null) {
            subscription.getHeaders().forEach(request::header);
        }

        try {
            request.bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
            return true;
        } catch (WebClientResponseException e) {
            // 2xx is success, anything else is failure
            if (e.getStatusCode().is2xxSuccessful()) {
                return true;
            }
            throw new RuntimeException("HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    /**
     * Build the notification payload based on content type.
     */
    private String buildNotificationPayload(Subscription subscription, SubscriptionNotification notification) {
        if ("full-resource".equals(subscription.getContentType())) {
            // Include full ClaimResponse bundle
            return JsonUtils.toJson(notification);
        } else {
            // id-only: just include reference
            return JsonUtils.toJson(new IdOnlyNotification(
                    notification.getNotificationId(),
                    notification.getPagwId(),
                    notification.getExternalReferenceId(),
                    notification.getStatus(),
                    notification.getOutcome(),
                    notification.getResourceReference(),
                    notification.getTimestamp()
            ));
        }
    }

    /**
     * Minimal notification for id-only content type.
     */
    private record IdOnlyNotification(
            String notificationId,
            String pagwId,
            String externalReferenceId,
            String status,
            String outcome,
            String resourceReference,
            Instant timestamp
    ) {}
}
