package com.elevance.pagw.mockpayer.controller;

import com.elevance.pagw.mockpayer.model.*;
import com.elevance.pagw.mockpayer.service.MockPayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Mock Payer API Controller
 * Simulates payer endpoints for PA requests
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class MockPayerController {

    private final MockPayerService mockPayerService;

    /**
     * Main PA submission endpoint
     * POST /api/x12/278
     */
    @PostMapping("/x12/278")
    public ResponseEntity<?> submitPriorAuth(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        
        log.info("Received PA request - correlationId: {}, requestId: {}", correlationId, requestId);
        log.debug("Request body: {}", request);
        
        return processPriorAuthRequest(request, correlationId);
    }

    /**
     * Alternative PA submission endpoint for pasapiconnector
     * POST /api/v1/claims/submit
     */
    @PostMapping("/v1/claims/submit")
    public ResponseEntity<?> submitClaim(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-PAGW-ID", required = false) String pagwId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-Target-System", required = false) String targetSystem,
            @RequestHeader(value = "X-Tenant", required = false) String tenant) {
        
        log.info("Received claim submission - pagwId: {}, correlationId: {}, targetSystem: {}, tenant: {}", 
                pagwId, correlationId, targetSystem, tenant);
        log.debug("Request body: {}", request);
        
        return processPriorAuthRequest(request, correlationId);
    }

    /**
     * Common processing logic for both endpoints
     */
    private ResponseEntity<?> processPriorAuthRequest(Map<String, Object> request, String correlationId) {
        // Check for test triggers in request body
        String requestJson = request.toString();
        
        try {
            // Simulate different scenarios based on request content
            if (requestJson.contains("TIMEOUT-TEST")) {
                return mockPayerService.handleTimeoutScenario(correlationId);
            }
            
            if (requestJson.contains("ERROR-TEST")) {
                return mockPayerService.handleErrorScenario(correlationId);
            }
            
            if (requestJson.contains("RATELIMIT-TEST")) {
                return mockPayerService.handleRateLimitScenario(correlationId);
            }
            
            if (requestJson.contains("UNAUTH-TEST")) {
                return mockPayerService.handleUnauthorizedScenario();
            }
            
            if (requestJson.contains("BADREQUEST-TEST")) {
                return mockPayerService.handleBadRequestScenario(correlationId);
            }
            
            if (requestJson.contains("DENY-TEST")) {
                return mockPayerService.handleDeniedScenario(request, correlationId);
            }
            
            if (requestJson.contains("PEND-TEST")) {
                return mockPayerService.handlePendedScenario(request, correlationId);
            }
            
            if (requestJson.contains("MOREINFO-TEST")) {
                return mockPayerService.handleMoreInfoScenario(request, correlationId);
            }
            
            // Default: Approved
            return mockPayerService.handleApprovedScenario(request, correlationId);
            
        } catch (Exception e) {
            log.error("Error processing PA request", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .error("INTERNAL_SERVER_ERROR")
                            .errorCode("E500")
                            .message("An unexpected error occurred")
                            .build());
        }
    }

    /**
     * PA status check endpoint
     * GET /api/status/{trackingId}
     */
    @GetMapping("/status/{trackingId}")
    public ResponseEntity<StatusResponse> checkStatus(
            @PathVariable String trackingId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        
        log.info("Status check for trackingId: {}", trackingId);
        
        return ResponseEntity.ok(StatusResponse.builder()
                .trackingId(trackingId)
                .status("APPROVED")
                .message("Prior authorization has been approved")
                .lastUpdated(java.time.Instant.now().toString())
                .build());
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Mock Payer API",
                "version", "1.0.0",
                "timestamp", java.time.Instant.now().toString()
        ));
    }
}
