package com.anthem.pagw.orchestrator.controller;

import com.anthem.pagw.orchestrator.model.InquiryRequest;
import com.anthem.pagw.orchestrator.model.InquiryResponse;
import com.anthem.pagw.orchestrator.model.PasRequest;
import com.anthem.pagw.orchestrator.model.PasResponse;
import com.anthem.pagw.orchestrator.service.ClaimModificationService;
import com.anthem.pagw.orchestrator.service.InquiryService;
import com.anthem.pagw.orchestrator.service.OrchestratorService;
import com.anthem.pagw.orchestrator.service.SubscriptionService;
import com.anthem.pagw.orchestrator.service.SubscriptionService.Subscription;
import com.anthem.pagw.orchestrator.service.SubscriptionService.SubscriptionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * REST Controller for PAS Orchestrator.
 * 
 * Implements Da Vinci PAS operations:
 * - $submit - Submit new prior authorization request
 * - $inquiry - Query status of pended authorization
 * - Update - Modify existing authorization
 * - Cancel - Cancel existing authorization
 * - Subscriptions - Register for pended authorization updates
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/">Da Vinci PAS IG</a>
 */
@RestController
@RequestMapping("/pas/v1")
public class OrchestratorController {
    
    private static final Logger log = LoggerFactory.getLogger(OrchestratorController.class);
    
    private final OrchestratorService orchestratorService;
    private final InquiryService inquiryService;
    private final ClaimModificationService claimModificationService;
    private final SubscriptionService subscriptionService;
    
    public OrchestratorController(
            OrchestratorService orchestratorService,
            InquiryService inquiryService,
            ClaimModificationService claimModificationService,
            SubscriptionService subscriptionService) {
        this.orchestratorService = orchestratorService;
        this.inquiryService = inquiryService;
        this.claimModificationService = claimModificationService;
        this.subscriptionService = subscriptionService;
    }
    
    /**
     * Submit a Prior Authorization request ($submit operation).
     * 
     * Per Da Vinci PAS IG:
     * - Attempts synchronous processing with 15-second response requirement
     * - Returns immediate ClaimResponse if decision can be made
     * - Returns "pended" status if async processing required
     * - Returns OperationOutcome for validation errors
     * 
     * @param fhirBundle The FHIR bundle containing the PA request
     * @param idempotencyKey Optional idempotency key (also uses Bundle.identifier)
     * @param tenant Tenant identifier
     * @param authenticatedProviderId Provider ID from OAuth token (for security validation)
     * @return ClaimResponse or OperationOutcome
     */
    @PostMapping(
            value = "/submit",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PasResponse> submitRequest(
            @RequestBody String fhirBundle,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Provider-Id", required = false) String authenticatedProviderId,
            @RequestHeader(value = "X-Sync-Processing", required = false, defaultValue = "true") boolean syncProcessing) {
        
        log.info("Received PAS submit request: tenant={}, correlationId={}, syncMode={}", 
                tenant, correlationId, syncProcessing);
        
        PasRequest request = PasRequest.builder()
                .fhirBundle(fhirBundle)
                .idempotencyKey(idempotencyKey)
                .tenant(tenant)
                .correlationId(correlationId)
                .requestType(PasRequest.TYPE_SUBMIT)
                .authenticatedProviderId(authenticatedProviderId)
                .syncProcessing(syncProcessing)
                .build();
        
        PasResponse response = orchestratorService.processRequest(request);
        
        // Return appropriate HTTP status based on response
        if ("error".equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * Inquiry for an existing Prior Authorization status ($inquiry operation).
     * 
     * Per Da Vinci PAS IG, supports querying by:
     * - PAGW tracking ID
     * - Payer tracking ID (preAuthRef)
     * - Bundle.identifier from original submission
     * - Patient + Provider + Service Date
     * 
     * @param fhirBundle The FHIR bundle containing the inquiry (optional)
     * @return ClaimResponse with current authorization status
     */
    @PostMapping(
            value = "/inquiry",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<InquiryResponse> inquiryRequest(
            @RequestBody(required = false) String fhirBundle,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Provider-Id", required = false) String authenticatedProviderId,
            @RequestParam(required = false) String pagwId,
            @RequestParam(required = false) String preAuthRef,
            @RequestParam(required = false) String bundleIdentifier,
            @RequestParam(required = false) String patientId,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String serviceDateFrom,
            @RequestParam(required = false) String serviceDateTo) {
        
        log.info("Received PAS inquiry request: tenant={}, pagwId={}, preAuthRef={}", 
                tenant, pagwId, preAuthRef);
        
        InquiryRequest request = InquiryRequest.builder()
                .fhirBundle(fhirBundle)
                .tenant(tenant)
                .correlationId(correlationId)
                .authenticatedProviderId(authenticatedProviderId)
                .pagwId(pagwId)
                .preAuthRef(preAuthRef)
                .bundleIdentifier(bundleIdentifier)
                .patientId(patientId)
                .providerId(providerId)
                .serviceDateFrom(serviceDateFrom != null ? LocalDate.parse(serviceDateFrom) : null)
                .serviceDateTo(serviceDateTo != null ? LocalDate.parse(serviceDateTo) : null)
                .build();
        
        InquiryResponse response = inquiryService.processInquiry(request);
        
        if (!response.isFound()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * Update an existing Prior Authorization.
     * 
     * Per Da Vinci PAS IG:
     * - Submits new Claim with Claim.related.claim pointing to original
     * - Uses certificationType extension with code "4" (Revised)
     * 
     * @param fhirBundle The FHIR bundle containing the updated Claim
     * @param relatedClaimId The original authorization ID to update
     * @return ClaimResponse confirming update submission
     */
    @PostMapping(
            value = "/update",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PasResponse> updateRequest(
            @RequestBody String fhirBundle,
            @RequestHeader(value = "X-Related-Claim-Id") String relatedClaimId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Provider-Id", required = false) String authenticatedProviderId) {
        
        log.info("Received PAS update request: relatedClaimId={}, tenant={}", relatedClaimId, tenant);
        
        PasRequest request = PasRequest.builder()
                .fhirBundle(fhirBundle)
                .relatedClaimId(relatedClaimId)
                .tenant(tenant)
                .correlationId(correlationId)
                .requestType(PasRequest.TYPE_UPDATE)
                .authenticatedProviderId(authenticatedProviderId)
                .certificationType(ClaimModificationService.CERT_TYPE_UPDATE)
                .build();
        
        PasResponse response = claimModificationService.processUpdate(request);
        
        if ("error".equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * Cancel an existing Prior Authorization.
     * 
     * Per Da Vinci PAS IG:
     * - Submits Claim with Claim.related.claim pointing to original
     * - Uses certificationType extension with code "3" (Cancel)
     * 
     * @param relatedClaimId The authorization ID to cancel
     * @return ClaimResponse confirming cancellation
     */
    @PostMapping(
            value = "/cancel",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PasResponse> cancelRequest(
            @RequestBody(required = false) String fhirBundle,
            @RequestHeader(value = "X-Related-Claim-Id") String relatedClaimId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Provider-Id", required = false) String authenticatedProviderId) {
        
        log.info("Received PAS cancel request: relatedClaimId={}, tenant={}", relatedClaimId, tenant);
        
        PasRequest request = PasRequest.builder()
                .fhirBundle(fhirBundle)
                .relatedClaimId(relatedClaimId)
                .tenant(tenant)
                .correlationId(correlationId)
                .requestType(PasRequest.TYPE_CANCEL)
                .authenticatedProviderId(authenticatedProviderId)
                .certificationType(ClaimModificationService.CERT_TYPE_CANCEL)
                .build();
        
        PasResponse response = claimModificationService.processCancel(request);
        
        if ("error".equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get status of a Prior Authorization request.
     * 
     * @param pagwId The PAGW request ID
     * @return Status information
     */
    @GetMapping(value = "/status/{pagwId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PasResponse> getStatus(@PathVariable String pagwId) {
        log.info("Status request for pagwId={}", pagwId);
        
        PasResponse response = orchestratorService.getStatus(pagwId);
        
        return ResponseEntity.ok(response);
    }
    
    // ==================== Subscription Endpoints ====================
    
    /**
     * Create a subscription for prior authorization status updates.
     * 
     * Per Da Vinci PAS IG (R5 Backport Subscriptions):
     * - Providers can subscribe to receive notifications when pended authorizations complete
     * - Supports REST-hook channel type (webhook callback)
     * 
     * @param pagwId Authorization to subscribe to (required)
     * @param endpoint Callback URL for notifications
     * @param secret Optional HMAC secret for webhook signature verification
     * @return Created subscription details
     */
    @PostMapping(value = "/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createSubscription(
            @RequestParam String pagwId,
            @RequestParam String endpoint,
            @RequestParam(required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestHeader(value = "X-Provider-Id", required = false) String providerId) {
        
        log.info("Creating subscription: pagwId={}, endpoint={}", pagwId, endpoint);
        
        try {
            SubscriptionRequest request = SubscriptionRequest.builder()
                    .pagwId(pagwId)
                    .channelType(Subscription.ChannelType.REST_HOOK)
                    .endpoint(endpoint)
                    .secret(secret)
                    .tenant(tenant)
                    .providerId(providerId)
                    .expiresAt(Instant.now().plusSeconds(7 * 24 * 60 * 60)) // 7 days
                    .build();
            
            Subscription subscription = subscriptionService.createSubscription(request);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "subscriptionId", subscription.getId(),
                    "status", subscription.getStatus().name(),
                    "pagwId", pagwId,
                    "endpoint", endpoint,
                    "expiresAt", subscription.getExpiresAt().toString(),
                    "topic", SubscriptionService.PAS_SUBSCRIPTION_TOPIC
            ));
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Get subscription details.
     * 
     * @param subscriptionId The subscription ID
     * @return Subscription details
     */
    @GetMapping(value = "/subscriptions/{subscriptionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSubscription(@PathVariable String subscriptionId) {
        log.info("Getting subscription: subscriptionId={}", subscriptionId);
        
        Optional<Subscription> subscription = subscriptionService.getSubscription(subscriptionId);
        
        if (subscription.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(subscriptionService.buildFhirSubscription(subscription.get()));
    }
    
    /**
     * Delete a subscription.
     * 
     * @param subscriptionId The subscription ID to delete
     * @return 204 No Content on success
     */
    @DeleteMapping(value = "/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> deleteSubscription(@PathVariable String subscriptionId) {
        log.info("Deleting subscription: subscriptionId={}", subscriptionId);
        
        boolean deleted = subscriptionService.deleteSubscription(subscriptionId);
        
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.noContent().build();
    }
    
    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("{\"status\":\"UP\"}");
    }
}
