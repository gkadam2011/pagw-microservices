package com.anthem.pagw.auth.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Provider context containing authentication and authorization details.
 * Passed to downstream services via API Gateway context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderContext {
    
    /**
     * OAuth2 client ID
     */
    private String clientId;
    
    /**
     * Tenant identifier (Carelon, Elevance, BCBSA, etc.)
     */
    private String tenant;
    
    /**
     * Provider name (organization name)
     */
    private String providerName;
    
    /**
     * Provider type: PAYER, PROVIDER, CLEARINGHOUSE, VENDOR
     */
    private String providerType;
    
    /**
     * National Provider Identifier (if applicable)
     */
    private String npi;
    
    /**
     * Tax ID / EIN
     */
    private String taxId;
    
    /**
     * Provider is active and can make requests
     */
    private boolean active;
    
    /**
     * API permissions granted to this provider
     */
    @Builder.Default
    private Set<String> permissions = new HashSet<>();
    
    /**
     * Rate limit (requests per minute)
     */
    @Builder.Default
    private int rateLimit = 100;
    
    /**
     * Token expiration time
     */
    private Instant tokenExpiry;
    
    /**
     * Token issuer (for multi-tenant validation)
     */
    private String issuer;
    
    /**
     * Subject from token
     */
    private String subject;
    
    /**
     * Additional claims from the token
     */
    private java.util.Map<String, Object> claims;
    
    /**
     * Environment (dev, perf, prod)
     */
    private String environment;

    /**
     * Check if provider is authorized for a specific API resource.
     */
    public boolean isAuthorizedFor(String resource) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        
        // Check for wildcard permission
        if (permissions.contains("*") || permissions.contains("/*")) {
            return true;
        }
        
        // Check for exact match
        if (permissions.contains(resource)) {
            return true;
        }
        
        // Check for prefix match (e.g., /Claim/* matches /Claim/submit)
        for (String perm : permissions) {
            if (perm.endsWith("/*")) {
                String prefix = perm.substring(0, perm.length() - 1);
                if (resource.startsWith(prefix)) {
                    return true;
                }
            }
        }
        
        return false;
    }
}
