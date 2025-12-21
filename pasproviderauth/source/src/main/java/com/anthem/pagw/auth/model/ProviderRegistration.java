package com.anthem.pagw.auth.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

/**
 * Provider registration record stored in DynamoDB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderRegistration {
    
    /**
     * OAuth2 client ID (partition key)
     */
    private String clientId;
    
    /**
     * Tenant identifier
     */
    private String tenant;
    
    /**
     * Provider organization name
     */
    private String providerName;
    
    /**
     * Provider type: PAYER, PROVIDER, CLEARINGHOUSE, VENDOR
     */
    private String providerType;
    
    /**
     * National Provider Identifier
     */
    private String npi;
    
    /**
     * Tax ID / EIN
     */
    private String taxId;
    
    /**
     * Provider is active
     */
    private boolean active;
    
    /**
     * API permissions
     */
    private Set<String> permissions;
    
    /**
     * Rate limit (requests per minute)
     */
    private int rateLimit;
    
    /**
     * Token issuer URL (for validation)
     */
    private String issuerUrl;
    
    /**
     * JWKS URL for token validation
     */
    private String jwksUrl;
    
    /**
     * Environment (dev, perf, prod)
     */
    private String environment;
    
    /**
     * Callback URL for async responses
     */
    private String callbackUrl;
    
    /**
     * Contact email
     */
    private String contactEmail;
    
    /**
     * Registration date
     */
    private Instant registeredAt;
    
    /**
     * Last updated date
     */
    private Instant updatedAt;
    
    /**
     * Last successful authentication
     */
    private Instant lastAuthAt;
    
    /**
     * Notes or comments
     */
    private String notes;
}
