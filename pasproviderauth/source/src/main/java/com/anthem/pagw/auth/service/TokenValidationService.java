package com.anthem.pagw.auth.service;

import com.anthem.pagw.auth.model.ProviderContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Service for validating provider tokens (JWT and API Keys).
 * Supports multiple issuers: Carelon, Elevance, BCBSA.
 */
public class TokenValidationService {

    private static final Logger log = LoggerFactory.getLogger(TokenValidationService.class);
    
    // Known issuers and their configurations
    private static final Map<String, IssuerConfig> ISSUER_CONFIGS = new HashMap<>();
    
    static {
        // Carelon (from prov-reg.txt)
        ISSUER_CONFIGS.put("carelon", IssuerConfig.builder()
                .name("Carelon")
                .tenant("carelon")
                .issuerUrl("https://perf.totalview.healthos.carelon.com")
                .tokenEndpoint("https://perf.totalview.healthos.carelon.com/client.oauth2/unregistered/api/v1/token")
                .build());
        
        // Elevance
        ISSUER_CONFIGS.put("elevance", IssuerConfig.builder()
                .name("Elevance")
                .tenant("elevance")
                .issuerUrl("https://api.elevancehealth.com")
                .build());
        
        // BCBSA
        ISSUER_CONFIGS.put("bcbsa", IssuerConfig.builder()
                .name("BCBSA")
                .tenant("bcbsa")
                .issuerUrl("https://api.bcbsa.com")
                .build());
    }
    
    private final SecretsManagerClient secretsClient;
    private final Map<String, SecretKey> signingKeys = new HashMap<>();
    private final String environment;

    public TokenValidationService() {
        this.environment = System.getenv().getOrDefault("ENVIRONMENT", "dev");
        this.secretsClient = SecretsManagerClient.builder()
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                .build();
        loadSigningKeys();
    }

    /**
     * Validate a token and extract provider context.
     * 
     * @param token JWT token or API key
     * @return ProviderContext if valid, null otherwise
     */
    public ProviderContext validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        // Check if it's an API key
        if (token.startsWith("apikey:")) {
            return validateApiKey(token.substring(7));
        }
        
        // Try JWT validation
        return validateJwt(token);
    }

    private ProviderContext validateJwt(String token) {
        try {
            // Try each issuer's signing key
            for (Map.Entry<String, IssuerConfig> entry : ISSUER_CONFIGS.entrySet()) {
                String issuerId = entry.getKey();
                IssuerConfig config = entry.getValue();
                
                SecretKey key = signingKeys.get(issuerId);
                if (key == null) {
                    continue;
                }
                
                try {
                    Jws<Claims> jws = Jwts.parser()
                            .verifyWith(key)
                            .build()
                            .parseSignedClaims(token);
                    
                    Claims claims = jws.getPayload();
                    
                    // Validate expiration
                    Date expiration = claims.getExpiration();
                    if (expiration != null && expiration.before(new Date())) {
                        log.warn("Token expired: issuer={}", issuerId);
                        continue;
                    }
                    
                    // Extract provider context
                    return ProviderContext.builder()
                            .clientId(claims.get("client_id", String.class))
                            .subject(claims.getSubject())
                            .issuer(claims.getIssuer())
                            .tenant(config.getTenant())
                            .providerName(claims.get("entity_name", String.class))
                            .tokenExpiry(expiration != null ? expiration.toInstant() : null)
                            .environment(environment)
                            .claims(new HashMap<>(claims))
                            .build();
                    
                } catch (JwtException e) {
                    // Try next issuer
                    log.debug("JWT validation failed for issuer {}: {}", issuerId, e.getMessage());
                }
            }
            
            // Try parsing without verification (for debugging/development)
            if ("dev".equals(environment)) {
                return parseUnverifiedToken(token);
            }
            
            log.warn("Token validation failed for all issuers");
            return null;
            
        } catch (Exception e) {
            log.error("Token validation error", e);
            return null;
        }
    }

    private ProviderContext parseUnverifiedToken(String token) {
        try {
            // Parse without signature verification (DEV ONLY)
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(payload, Map.class);
            
            log.warn("DEV MODE: Parsed unverified token for client_id={}", claims.get("client_id"));
            
            return ProviderContext.builder()
                    .clientId((String) claims.get("client_id"))
                    .subject((String) claims.get("sub"))
                    .issuer((String) claims.get("iss"))
                    .providerName((String) claims.get("entity_name"))
                    .tenant(detectTenant((String) claims.get("iss")))
                    .environment(environment)
                    .claims(claims)
                    .active(true) // Allow in dev
                    .permissions(Set.of("/*")) // Full access in dev
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to parse unverified token", e);
            return null;
        }
    }

    private ProviderContext validateApiKey(String apiKey) {
        try {
            // Look up API key in Secrets Manager or DynamoDB
            // Format: tenant:key (e.g., "carelon:abc123")
            String[] parts = apiKey.split(":", 2);
            String tenant = parts.length > 1 ? parts[0] : "unknown";
            String key = parts.length > 1 ? parts[1] : apiKey;
            
            // In production, validate against stored API keys
            // For now, return a basic context for dev
            if ("dev".equals(environment)) {
                return ProviderContext.builder()
                        .clientId("apikey-" + key.substring(0, Math.min(8, key.length())))
                        .tenant(tenant)
                        .providerType("API_KEY")
                        .environment(environment)
                        .active(true)
                        .permissions(Set.of("/*"))
                        .build();
            }
            
            // Production: validate against stored keys
            return validateStoredApiKey(tenant, key);
            
        } catch (Exception e) {
            log.error("API key validation error", e);
            return null;
        }
    }

    private ProviderContext validateStoredApiKey(String tenant, String key) {
        // TODO: Implement API key lookup from DynamoDB
        log.warn("API key validation not implemented for production");
        return null;
    }

    private String detectTenant(String issuer) {
        if (issuer == null) return "unknown";
        
        if (issuer.contains("carelon")) return "carelon";
        if (issuer.contains("elevance")) return "elevance";
        if (issuer.contains("bcbsa")) return "bcbsa";
        
        return "unknown";
    }

    private void loadSigningKeys() {
        try {
            // Load signing keys from Secrets Manager
            for (String issuerId : ISSUER_CONFIGS.keySet()) {
                String secretName = String.format("pagw/%s/jwt-signing-key", issuerId);
                try {
                    GetSecretValueRequest request = GetSecretValueRequest.builder()
                            .secretId(secretName)
                            .build();
                    
                    String secretValue = secretsClient.getSecretValue(request).secretString();
                    SecretKey key = new SecretKeySpec(
                            secretValue.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"
                    );
                    signingKeys.put(issuerId, key);
                    log.info("Loaded signing key for issuer: {}", issuerId);
                    
                } catch (Exception e) {
                    log.warn("Failed to load signing key for {}: {}", issuerId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load signing keys", e);
        }
    }

    @lombok.Builder
    @lombok.Data
    private static class IssuerConfig {
        private String name;
        private String tenant;
        private String issuerUrl;
        private String tokenEndpoint;
        private String jwksUrl;
    }
}
