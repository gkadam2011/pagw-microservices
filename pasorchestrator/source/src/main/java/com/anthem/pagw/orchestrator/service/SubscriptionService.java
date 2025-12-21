package com.anthem.pagw.orchestrator.service;

import com.anthem.pagw.core.model.RequestTracker;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscription Service for Da Vinci PAS pended authorization notifications.
 * 
 * Implements R5 Backport Subscriptions to notify providers when pended
 * authorizations are completed.
 * 
 * Subscription Types:
 * 1. REST-hook - POST notification to provider endpoint
 * 2. WebSocket - Real-time push (future)
 * 3. Email - Email notification (future)
 * 
 * @see <a href="http://hl7.org/fhir/uv/subscriptions-backport/">Subscriptions R5 Backport</a>
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/specification.html#pended-authorization-responses">Pended Authorizations</a>
 */
@Service
public class SubscriptionService {
    
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    
    /**
     * PAS Subscription topic for authorization status changes.
     */
    public static final String PAS_SUBSCRIPTION_TOPIC = "http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic";
    
    // In-memory subscription store (replace with database in production)
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    
    // Map of pagwId -> Set<subscriptionId> for quick lookup
    private final Map<String, Set<String>> authorizationSubscriptions = new ConcurrentHashMap<>();
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RequestTrackerService requestTrackerService;
    
    @Value("${pagw.subscriptions.enabled:true}")
    private boolean subscriptionsEnabled;
    
    @Value("${pagw.subscriptions.max-retries:3}")
    private int maxRetries;
    
    @Value("${pagw.base-url:https://api.pagw.example.com}")
    private String baseUrl;
    
    public SubscriptionService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            RequestTrackerService requestTrackerService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.requestTrackerService = requestTrackerService;
    }
    
    /**
     * Create a subscription for authorization status updates.
     * 
     * @param request The subscription request
     * @return Created subscription with ID
     */
    public Subscription createSubscription(SubscriptionRequest request) {
        log.info("Creating subscription: pagwId={}, endpoint={}", 
                request.getPagwId(), request.getEndpoint());
        
        // Validate the authorization exists
        if (request.getPagwId() != null) {
            Optional<RequestTracker> tracker = requestTrackerService.findByPagwId(request.getPagwId());
            if (tracker.isEmpty()) {
                throw new IllegalArgumentException("Authorization not found: " + request.getPagwId());
            }
        }
        
        String subscriptionId = UUID.randomUUID().toString();
        
        Subscription subscription = Subscription.builder()
                .id(subscriptionId)
                .status(Subscription.Status.ACTIVE)
                .topic(PAS_SUBSCRIPTION_TOPIC)
                .channelType(request.getChannelType())
                .endpoint(request.getEndpoint())
                .pagwId(request.getPagwId())
                .tenant(request.getTenant())
                .providerId(request.getProviderId())
                .secret(request.getSecret())
                .headers(request.getHeaders())
                .createdAt(Instant.now())
                .expiresAt(request.getExpiresAt() != null ? request.getExpiresAt() : 
                        Instant.now().plusSeconds(7 * 24 * 60 * 60)) // Default 7 days
                .build();
        
        subscriptions.put(subscriptionId, subscription);
        
        // Index by pagwId for quick notification lookup
        if (request.getPagwId() != null) {
            authorizationSubscriptions
                    .computeIfAbsent(request.getPagwId(), k -> ConcurrentHashMap.newKeySet())
                    .add(subscriptionId);
        }
        
        log.info("Subscription created: subscriptionId={}, pagwId={}", subscriptionId, request.getPagwId());
        
        return subscription;
    }
    
    /**
     * Get a subscription by ID.
     */
    public Optional<Subscription> getSubscription(String subscriptionId) {
        return Optional.ofNullable(subscriptions.get(subscriptionId));
    }
    
    /**
     * Delete a subscription.
     */
    public boolean deleteSubscription(String subscriptionId) {
        Subscription subscription = subscriptions.remove(subscriptionId);
        if (subscription != null && subscription.getPagwId() != null) {
            Set<String> subs = authorizationSubscriptions.get(subscription.getPagwId());
            if (subs != null) {
                subs.remove(subscriptionId);
            }
        }
        return subscription != null;
    }
    
    /**
     * Notify all subscribers when an authorization status changes.
     * Called by services when processing completes.
     * 
     * @param pagwId The authorization tracking ID
     * @param newStatus The new status
     * @param claimResponseBundle The final ClaimResponse bundle (if completed)
     */
    public void notifySubscribers(String pagwId, String newStatus, String claimResponseBundle) {
        if (!subscriptionsEnabled) {
            log.debug("Subscriptions disabled, skipping notification: pagwId={}", pagwId);
            return;
        }
        
        Set<String> subscriptionIds = authorizationSubscriptions.get(pagwId);
        if (subscriptionIds == null || subscriptionIds.isEmpty()) {
            log.debug("No subscriptions for pagwId={}", pagwId);
            return;
        }
        
        log.info("Notifying {} subscribers for pagwId={}, status={}", 
                subscriptionIds.size(), pagwId, newStatus);
        
        for (String subscriptionId : subscriptionIds) {
            Subscription subscription = subscriptions.get(subscriptionId);
            if (subscription != null && subscription.getStatus() == Subscription.Status.ACTIVE) {
                sendNotification(subscription, pagwId, newStatus, claimResponseBundle);
            }
        }
    }
    
    /**
     * Send notification to a single subscriber.
     */
    private void sendNotification(Subscription subscription, String pagwId, String newStatus, String claimResponseBundle) {
        log.debug("Sending notification: subscriptionId={}, endpoint={}", 
                subscription.getId(), subscription.getEndpoint());
        
        try {
            String notificationBundle = buildNotificationBundle(subscription, pagwId, newStatus, claimResponseBundle);
            
            switch (subscription.getChannelType()) {
                case REST_HOOK -> sendRestHookNotification(subscription, notificationBundle);
                case WEBSOCKET -> log.warn("WebSocket notifications not yet implemented");
                case EMAIL -> log.warn("Email notifications not yet implemented");
            }
            
        } catch (Exception e) {
            log.error("Failed to send notification: subscriptionId={}, pagwId={}", 
                    subscription.getId(), pagwId, e);
            handleNotificationFailure(subscription);
        }
    }
    
    /**
     * Send REST-hook notification.
     */
    private void sendRestHookNotification(Subscription subscription, String notificationBundle) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Add custom headers
        if (subscription.getHeaders() != null) {
            subscription.getHeaders().forEach(headers::add);
        }
        
        // Add HMAC signature if secret is configured
        if (subscription.getSecret() != null) {
            String signature = calculateHmacSignature(notificationBundle, subscription.getSecret());
            headers.add("X-Hub-Signature", "sha256=" + signature);
        }
        
        HttpEntity<String> request = new HttpEntity<>(notificationBundle, headers);
        
        int retries = 0;
        while (retries < maxRetries) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        subscription.getEndpoint(),
                        HttpMethod.POST,
                        request,
                        String.class);
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Notification sent successfully: subscriptionId={}, status={}", 
                            subscription.getId(), response.getStatusCode());
                    return;
                } else {
                    log.warn("Notification returned non-2xx: subscriptionId={}, status={}", 
                            subscription.getId(), response.getStatusCode());
                }
            } catch (RestClientException e) {
                log.warn("Notification attempt {} failed: subscriptionId={}", 
                        retries + 1, subscription.getId(), e);
            }
            retries++;
            
            // Exponential backoff
            try {
                Thread.sleep((long) Math.pow(2, retries) * 1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        log.error("All notification retries failed: subscriptionId={}", subscription.getId());
        handleNotificationFailure(subscription);
    }
    
    /**
     * Build FHIR notification bundle per R5 Backport spec.
     */
    private String buildNotificationBundle(Subscription subscription, String pagwId, String newStatus, String claimResponseBundle) {
        try {
            ObjectNode bundle = objectMapper.createObjectNode();
            bundle.put("resourceType", "Bundle");
            bundle.put("id", UUID.randomUUID().toString());
            bundle.put("type", "subscription-notification");
            bundle.put("timestamp", Instant.now().toString());
            
            ArrayNode entries = bundle.putArray("entry");
            
            // SubscriptionStatus entry
            ObjectNode statusEntry = entries.addObject();
            statusEntry.put("fullUrl", "urn:uuid:" + UUID.randomUUID());
            
            ObjectNode subscriptionStatus = statusEntry.putObject("resource");
            subscriptionStatus.put("resourceType", "SubscriptionStatus");
            subscriptionStatus.put("status", "active");
            subscriptionStatus.put("type", "event-notification");
            subscriptionStatus.put("eventsSinceSubscriptionStart", "1");
            
            ObjectNode subscriptionRef = subscriptionStatus.putObject("subscription");
            subscriptionRef.put("reference", "Subscription/" + subscription.getId());
            
            ObjectNode topicRef = subscriptionStatus.putObject("topic");
            topicRef.put("reference", PAS_SUBSCRIPTION_TOPIC);
            
            // Notification event
            ArrayNode notificationEvents = subscriptionStatus.putArray("notificationEvent");
            ObjectNode event = notificationEvents.addObject();
            event.put("eventNumber", "1");
            event.put("timestamp", Instant.now().toString());
            
            ObjectNode focus = event.putObject("focus");
            focus.put("reference", "ClaimResponse/" + pagwId);
            
            // Include the ClaimResponse if available
            if (claimResponseBundle != null) {
                ObjectNode responseEntry = entries.addObject();
                responseEntry.put("fullUrl", baseUrl + "/fhir/ClaimResponse/" + pagwId);
                responseEntry.set("resource", objectMapper.readTree(claimResponseBundle));
            } else {
                // Include minimal ClaimResponse with status
                ObjectNode responseEntry = entries.addObject();
                responseEntry.put("fullUrl", baseUrl + "/fhir/ClaimResponse/" + pagwId);
                
                ObjectNode claimResponse = responseEntry.putObject("resource");
                claimResponse.put("resourceType", "ClaimResponse");
                claimResponse.put("id", pagwId);
                claimResponse.put("status", "active");
                claimResponse.put("outcome", mapStatusToOutcome(newStatus));
                claimResponse.put("preAuthRef", pagwId);
            }
            
            return objectMapper.writeValueAsString(bundle);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to build notification bundle", e);
            throw new RuntimeException("Failed to build notification bundle", e);
        }
    }
    
    /**
     * Map internal status to FHIR outcome.
     */
    private String mapStatusToOutcome(String status) {
        return switch (status) {
            case RequestTracker.STATUS_COMPLETED -> "complete";
            case RequestTracker.STATUS_ERROR, RequestTracker.STATUS_FAILED -> "error";
            case RequestTracker.STATUS_CANCELLED -> "cancelled";
            default -> "partial"; // Still processing
        };
    }
    
    /**
     * Calculate HMAC-SHA256 signature for notification.
     */
    private String calculateHmacSignature(String payload, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Failed to calculate HMAC signature", e);
            return "";
        }
    }
    
    /**
     * Handle notification failure.
     */
    private void handleNotificationFailure(Subscription subscription) {
        subscription.setFailureCount(subscription.getFailureCount() + 1);
        
        // Disable subscription after too many failures
        if (subscription.getFailureCount() >= 10) {
            subscription.setStatus(Subscription.Status.ERROR);
            log.warn("Subscription disabled due to failures: subscriptionId={}", subscription.getId());
        }
    }
    
    /**
     * Build FHIR Subscription resource.
     */
    public String buildFhirSubscription(Subscription subscription) {
        try {
            ObjectNode resource = objectMapper.createObjectNode();
            resource.put("resourceType", "Subscription");
            resource.put("id", subscription.getId());
            resource.put("status", subscription.getStatus().name().toLowerCase());
            resource.put("topic", subscription.getTopic());
            resource.put("end", subscription.getExpiresAt().toString());
            
            // Channel
            ObjectNode channel = resource.putObject("channel");
            channel.put("type", subscription.getChannelType().name().toLowerCase().replace("_", "-"));
            channel.put("endpoint", subscription.getEndpoint());
            
            ArrayNode channelHeaders = channel.putArray("header");
            if (subscription.getHeaders() != null) {
                subscription.getHeaders().forEach((k, v) -> channelHeaders.add(k + ": " + v));
            }
            
            // Filter criteria
            ArrayNode filterBy = resource.putArray("filterBy");
            if (subscription.getPagwId() != null) {
                ObjectNode filter = filterBy.addObject();
                filter.put("filterParameter", "focus");
                filter.put("value", "ClaimResponse/" + subscription.getPagwId());
            }
            
            return objectMapper.writeValueAsString(resource);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to build FHIR Subscription", e);
            return "{\"error\": \"Failed to build subscription\"}";
        }
    }
    
    // Inner classes
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Subscription {
        private String id;
        private Status status;
        private String topic;
        private ChannelType channelType;
        private String endpoint;
        private String pagwId;
        private String tenant;
        private String providerId;
        private String secret;
        private Map<String, String> headers;
        private Instant createdAt;
        private Instant expiresAt;
        private int failureCount;
        
        public enum Status {
            REQUESTED, ACTIVE, ERROR, OFF
        }
        
        public enum ChannelType {
            REST_HOOK, WEBSOCKET, EMAIL
        }
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SubscriptionRequest {
        private String pagwId;
        private Subscription.ChannelType channelType;
        private String endpoint;
        private String tenant;
        private String providerId;
        private String secret;
        private Map<String, String> headers;
        private Instant expiresAt;
    }
}
