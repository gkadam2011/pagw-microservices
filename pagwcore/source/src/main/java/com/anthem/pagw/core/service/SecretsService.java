package com.anthem.pagw.core.service;

import com.anthem.pagw.core.PagwProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for fetching secrets from AWS Secrets Manager.
 * Includes caching to reduce API calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecretsService {

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedSecret> secretCache = new ConcurrentHashMap<>();
    
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    public String getSecret(String secretName) {
        CachedSecret cached = secretCache.get(secretName);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretValue = response.secretString();
            
            secretCache.put(secretName, new CachedSecret(secretValue));
            log.debug("Fetched and cached secret: {}", secretName);
            
            return secretValue;
        } catch (Exception e) {
            log.error("Failed to fetch secret: {}", secretName, e);
            throw new RuntimeException("Failed to fetch secret: " + secretName, e);
        }
    }

    public DatabaseCredentials getDatabaseCredentials(String secretName) {
        String secretJson = getSecret(secretName);
        try {
            JsonNode node = objectMapper.readTree(secretJson);
            return DatabaseCredentials.builder()
                    .username(node.get("user").asText())
                    .password(node.get("password").asText())
                    .host(node.has("host") ? node.get("host").asText() : null)
                    .port(node.has("port") ? node.get("port").asInt() : 5432)
                    .dbname(node.has("dbname") ? node.get("dbname").asText() : null)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse database credentials from secret: {}", secretName, e);
            throw new RuntimeException("Failed to parse database credentials", e);
        }
    }

    public void clearCache() {
        secretCache.clear();
        log.info("Secret cache cleared");
    }

    private static class CachedSecret {
        final String value;
        final long expiresAt;

        CachedSecret(String value) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class DatabaseCredentials {
        private String username;
        private String password;
        private String host;
        private int port;
        private String dbname;
    }
}
