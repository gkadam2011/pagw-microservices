package com.anthem.pagw.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.anthem.pagw.auth.model.AuthPolicy;
import com.anthem.pagw.auth.model.AuthRequest;
import com.anthem.pagw.auth.model.ProviderContext;
import com.anthem.pagw.auth.service.ProviderRegistryService;
import com.anthem.pagw.auth.service.TokenValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS Lambda Authorizer for API Gateway.
 * Validates provider tokens and returns IAM policy for authorization.
 * 
 * Supports multiple providers:
 * - Carelon (OAuth2 JWT)
 * - Elevance (OAuth2 JWT)  
 * - BCBSA (OAuth2 JWT)
 * - Custom API Keys
 */
public class ProviderAuthHandler implements RequestHandler<AuthRequest, AuthPolicy> {

    private static final Logger log = LoggerFactory.getLogger(ProviderAuthHandler.class);
    
    private final TokenValidationService tokenService;
    private final ProviderRegistryService registryService;

    public ProviderAuthHandler() {
        this.tokenService = new TokenValidationService();
        this.registryService = new ProviderRegistryService();
    }

    // For testing
    public ProviderAuthHandler(TokenValidationService tokenService, ProviderRegistryService registryService) {
        this.tokenService = tokenService;
        this.registryService = registryService;
    }

    @Override
    public AuthPolicy handleRequest(AuthRequest request, Context context) {
        log.info("Processing auth request: methodArn={}", request.getMethodArn());
        
        try {
            // Extract token from Authorization header
            String token = extractToken(request);
            if (token == null) {
                log.warn("No token provided");
                return denyPolicy(request.getMethodArn(), "anonymous");
            }
            
            // Validate token and extract claims
            ProviderContext providerContext = tokenService.validateToken(token);
            if (providerContext == null) {
                log.warn("Token validation failed");
                return denyPolicy(request.getMethodArn(), "invalid-token");
            }
            
            // Check provider registration and permissions
            ProviderContext enrichedContext = registryService.getProviderDetails(providerContext);
            if (enrichedContext == null || !enrichedContext.isActive()) {
                log.warn("Provider not registered or inactive: clientId={}", providerContext.getClientId());
                return denyPolicy(request.getMethodArn(), providerContext.getClientId());
            }
            
            // Check if provider is authorized for this API
            String resource = extractResource(request.getMethodArn());
            if (!enrichedContext.isAuthorizedFor(resource)) {
                log.warn("Provider not authorized for resource: clientId={}, resource={}", 
                        enrichedContext.getClientId(), resource);
                return denyPolicy(request.getMethodArn(), enrichedContext.getClientId());
            }
            
            log.info("Authorization successful: clientId={}, tenant={}, provider={}", 
                    enrichedContext.getClientId(), enrichedContext.getTenant(), enrichedContext.getProviderName());
            
            return allowPolicy(request.getMethodArn(), enrichedContext);
            
        } catch (Exception e) {
            log.error("Authorization error", e);
            return denyPolicy(request.getMethodArn(), "error");
        }
    }

    private String extractToken(AuthRequest request) {
        // Try Authorization header first
        String authHeader = request.getAuthorizationToken();
        if (authHeader != null && !authHeader.isEmpty()) {
            if (authHeader.toLowerCase().startsWith("bearer ")) {
                return authHeader.substring(7);
            }
            return authHeader;
        }
        
        // Try headers map
        if (request.getHeaders() != null) {
            String headerToken = request.getHeaders().get("Authorization");
            if (headerToken != null && headerToken.toLowerCase().startsWith("bearer ")) {
                return headerToken.substring(7);
            }
            
            // Try X-API-Key header
            String apiKey = request.getHeaders().get("X-API-Key");
            if (apiKey != null && !apiKey.isEmpty()) {
                return "apikey:" + apiKey;
            }
        }
        
        return null;
    }

    private String extractResource(String methodArn) {
        // arn:aws:execute-api:region:account:api-id/stage/method/resource
        if (methodArn == null) return "*";
        String[] parts = methodArn.split(":");
        if (parts.length >= 6) {
            String[] apiParts = parts[5].split("/");
            if (apiParts.length >= 4) {
                return "/" + String.join("/", java.util.Arrays.copyOfRange(apiParts, 3, apiParts.length));
            }
        }
        return "*";
    }

    private AuthPolicy allowPolicy(String methodArn, ProviderContext context) {
        AuthPolicy policy = new AuthPolicy();
        policy.setPrincipalId(context.getClientId());
        
        // Build policy document
        AuthPolicy.PolicyDocument policyDoc = new AuthPolicy.PolicyDocument();
        policyDoc.setVersion("2012-10-17");
        
        AuthPolicy.Statement statement = new AuthPolicy.Statement();
        statement.setAction("execute-api:Invoke");
        statement.setEffect("Allow");
        statement.setResource(buildResourceArn(methodArn));
        
        policyDoc.setStatement(java.util.List.of(statement));
        policy.setPolicyDocument(policyDoc);
        
        // Add context for downstream services
        Map<String, Object> authContext = new HashMap<>();
        authContext.put("clientId", context.getClientId());
        authContext.put("tenant", context.getTenant());
        authContext.put("providerName", context.getProviderName());
        authContext.put("providerType", context.getProviderType());
        authContext.put("npi", context.getNpi() != null ? context.getNpi() : "");
        authContext.put("permissions", String.join(",", context.getPermissions()));
        authContext.put("rateLimit", String.valueOf(context.getRateLimit()));
        policy.setContext(authContext);
        
        return policy;
    }

    private AuthPolicy denyPolicy(String methodArn, String principalId) {
        AuthPolicy policy = new AuthPolicy();
        policy.setPrincipalId(principalId);
        
        AuthPolicy.PolicyDocument policyDoc = new AuthPolicy.PolicyDocument();
        policyDoc.setVersion("2012-10-17");
        
        AuthPolicy.Statement statement = new AuthPolicy.Statement();
        statement.setAction("execute-api:Invoke");
        statement.setEffect("Deny");
        statement.setResource(buildResourceArn(methodArn));
        
        policyDoc.setStatement(java.util.List.of(statement));
        policy.setPolicyDocument(policyDoc);
        
        return policy;
    }

    private String buildResourceArn(String methodArn) {
        // Allow all methods on this API
        if (methodArn == null) return "*";
        String[] parts = methodArn.split(":");
        if (parts.length >= 6) {
            String[] apiParts = parts[5].split("/");
            if (apiParts.length >= 2) {
                // arn:aws:execute-api:region:account:api-id/stage/*
                return String.join(":", java.util.Arrays.copyOfRange(parts, 0, 5)) + 
                       ":" + apiParts[0] + "/" + apiParts[1] + "/*";
            }
        }
        return methodArn;
    }
}
