package com.anthem.pagw.orchestrator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PAS Request model for incoming requests.
 * 
 * Per Da Vinci PAS IG, supports:
 * - $submit (new prior auth request)
 * - $inquiry (check status of pended request) 
 * - Update (modify existing prior auth)
 * - Cancel (cancel existing prior auth)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasRequest {
    
    private String fhirBundle;
    private String idempotencyKey;
    private String tenant;
    private String correlationId;
    private String requestType;
    
    /**
     * Provider identifier from OAuth token (Lambda Authorizer context: X-Provider-Id).
     * Used to validate Claim.provider.identifier matches per Da Vinci PAS requirements.
     */
    private String authenticatedProviderId;
    
    /**
     * Provider NPI from OAuth token (Lambda Authorizer context: X-Provider-Npi).
     * Extracted from oauth_provider_registry.npis array by Lambda.
     */
    private String providerNpi;
    
    /**
     * Tenant from OAuth token (Lambda Authorizer context: X-Tenant).
     * Used for tenant isolation (elevance, carelon, etc.).
     */
    private String providerTenant;
    
    /**
     * Entity name from OAuth provider registry (Lambda Authorizer context: X-Entity-Name).
     * Human-readable provider organization name.
     */
    private String entityName;
    
    /**
     * Allowed APIs from OAuth provider registry (Lambda Authorizer context: X-Allowed-Apis).
     * Comma-separated list of entitled APIs (PAS_SUBMIT, PAS_INQUIRE, SUBSCRIBE, CDEX).
     */
    private String allowedApis;
    
    /**
     * Whether to process synchronously (Da Vinci PAS default) or queue for async.
     * Default is true for compliance with 15-second response requirement.
     */
    @Builder.Default
    private boolean syncProcessing = true;
    
    /**
     * Original prior auth reference for updates/cancels.
     * Maps to Claim.related.claim.
     */
    private String relatedClaimId;
    
    /**
     * Certificate type for updates.
     * 1 = Initial, 3 = Cancel, 4 = Update
     */
    private String certificationType;
    
    // Request type constants matching RequestTracker
    public static final String TYPE_SUBMIT = "SUBMIT";
    public static final String TYPE_INQUIRY = "INQUIRY";
    public static final String TYPE_CANCEL = "CANCEL";
    public static final String TYPE_UPDATE = "UPDATE";
}
