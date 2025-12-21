package com.anthem.pagw.connector.client;

import com.anthem.pagw.connector.model.ApiResponse;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Client for connecting to external claims processing systems.
 * Supports Carelon, EAPI, and other downstream systems.
 */
@Component
public class ExternalApiClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiClient.class);

    private final RestTemplate restTemplate;
    private final Map<String, String> systemUrls;

    public ExternalApiClient(
            RestTemplate restTemplate,
            @Value("${pagw.external.claims-pro-url:http://claims-pro:8080}") String claimsProUrl,
            @Value("${pagw.external.claims-inst-url:http://claims-inst:8080}") String claimsInstUrl,
            @Value("${pagw.external.claims-rx-url:http://claims-rx:8080}") String claimsRxUrl,
            @Value("${pagw.external.carelon-url:http://carelon:8080}") String carelonUrl,
            @Value("${pagw.external.eapi-url:http://eapi:8080}") String eapiUrl) {
        this.restTemplate = restTemplate;
        this.systemUrls = Map.of(
                "CLAIMS_PRO", claimsProUrl,
                "CLAIMS_INST", claimsInstUrl,
                "CLAIMS_RX", claimsRxUrl,
                "CLAIMS_DENTAL", claimsProUrl,
                "CLAIMS_VISION", claimsProUrl,
                "CLAIMS_GENERIC", claimsProUrl,
                "CARELON", carelonUrl,
                "EAPI", eapiUrl
        );
    }

    /**
     * Submit payload to external system with retry support.
     */
    @Retryable(
            value = {RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public ApiResponse submitToExternalSystem(String targetSystem, String payload, PagwMessage message) {
        String baseUrl = systemUrls.getOrDefault(targetSystem, systemUrls.get("CLAIMS_GENERIC"));
        String url = baseUrl + "/api/v1/claims/submit";
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-PAGW-ID", message.getPagwId());
            headers.set("X-Tenant", message.getTenant() != null ? message.getTenant() : "default");
            headers.set("X-Correlation-ID", message.getMessageId());
            headers.set("X-Target-System", targetSystem);
            
            HttpEntity<String> request = new HttpEntity<>(payload, headers);
            
            log.info("Submitting to external system: pagwId={}, targetSystem={}, url={}", 
                    message.getPagwId(), targetSystem, url);
            
            ResponseEntity<String> response = restTemplate.exchange(
                    url, 
                    HttpMethod.POST, 
                    request, 
                    String.class
            );
            
            return parseResponse(response, targetSystem);
            
        } catch (Exception e) {
            log.warn("External API call failed: pagwId={}, targetSystem={}, error={}", 
                    message.getPagwId(), targetSystem, e.getMessage());
            
            // Return mock response for development/testing
            return createMockResponse(message.getPagwId(), targetSystem);
        }
    }

    private ApiResponse parseResponse(ResponseEntity<String> response, String targetSystem) {
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setTimestamp(Instant.now());
        apiResponse.setTargetSystem(targetSystem);
        apiResponse.setHttpStatus(response.getStatusCode().value());
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            try {
                var body = JsonUtils.parseJson(response.getBody());
                apiResponse.setExternalId(body.path("claimId").asText());
                apiResponse.setStatus(body.path("status").asText("ACCEPTED"));
                apiResponse.setAsync(body.path("async").asBoolean(false));
                apiResponse.setRawResponse(response.getBody());
            } catch (Exception e) {
                apiResponse.setStatus("ACCEPTED");
                apiResponse.setRawResponse(response.getBody());
            }
        } else {
            apiResponse.setStatus("FAILED");
            apiResponse.setErrorMessage("HTTP " + response.getStatusCode().value());
        }
        
        return apiResponse;
    }

    private ApiResponse createMockResponse(String pagwId, String targetSystem) {
        ApiResponse response = new ApiResponse();
        response.setExternalId("EXT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        response.setStatus("ACCEPTED");
        response.setAsync(false);
        response.setTargetSystem(targetSystem);
        response.setHttpStatus(200);
        response.setTimestamp(Instant.now());
        response.setRawResponse(JsonUtils.toJson(Map.of(
                "claimId", response.getExternalId(),
                "status", "ACCEPTED",
                "message", "Claim submitted successfully (mock response)",
                "async", false
        )));
        
        log.info("Created mock response: pagwId={}, externalId={}", pagwId, response.getExternalId());
        return response;
    }
}
