package com.anthem.pagw.subscription.controller;

import com.anthem.pagw.subscription.model.Subscription;
import com.anthem.pagw.subscription.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing subscriptions.
 * Provides endpoints for CRUD operations on subscription registrations.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /**
     * Create a new subscription.
     * POST /api/v1/subscriptions
     */
    @PostMapping
    public ResponseEntity<Subscription> createSubscription(
            @RequestBody Subscription subscription,
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        log.info("Creating subscription for tenant: {}, endpoint: {}", 
                tenantId, subscription.getEndpoint());
        
        subscription.setTenantId(tenantId);
        
        // Validate required fields
        if (subscription.getEndpoint() == null || subscription.getEndpoint().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        
        // Set defaults
        if (subscription.getChannelType() == null) {
            subscription.setChannelType("rest-hook");
        }
        if (subscription.getContentType() == null) {
            subscription.setContentType("id-only");
        }
        
        Subscription created = subscriptionService.createSubscription(subscription);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Get subscription by ID.
     * GET /api/v1/subscriptions/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Subscription> getSubscription(@PathVariable String id) {
        return subscriptionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List all subscriptions for a tenant.
     * GET /api/v1/subscriptions
     */
    @GetMapping
    public ResponseEntity<List<Subscription>> listSubscriptions(
            @RequestHeader("X-Tenant-Id") String tenantId) {
        
        List<Subscription> subscriptions = subscriptionService
                .findActiveSubscriptionsForTenant(tenantId);
        return ResponseEntity.ok(subscriptions);
    }

    /**
     * Delete (deactivate) a subscription.
     * DELETE /api/v1/subscriptions/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubscription(@PathVariable String id) {
        subscriptionService.deactivateSubscription(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
