package com.anthem.pagw.orchestrator.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult.Disposition;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult.ValidationError;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Synchronous Processing Service for Da Vinci PAS compliance.
 * 
 * Per Da Vinci PAS Implementation Guide, the entire request-response cycle
 * MUST complete within 15 seconds. This service orchestrates synchronous calls
 * to downstream services:
 * 
 * 1. Request Parser - Parse FHIR Bundle, extract key fields
 * 2. Business Validator - Apply business rules, check eligibility
 * 3. Decision Engine - Make immediate determination if possible
 * 
 * If a decision can be made within the timeout, return complete ClaimResponse.
 * If not, return "pended" status and queue for async processing.
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/specification.html">Da Vinci PAS IG</a>
 */
@Service
public class SyncProcessingService {
    
    private static final Logger log = LoggerFactory.getLogger(SyncProcessingService.class);
    
    /**
     * Da Vinci PAS mandates 15-second response time.
     * We use 13 seconds to leave buffer for response serialization.
     */
    private static final int SYNC_TIMEOUT_SECONDS = 13;
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final S3Service s3Service;
    private final RequestTrackerService requestTrackerService;
    private final PagwProperties properties;
    private final ExecutorService executor;
    
    @Value("${pagw.sync.requestParserUrl:http://pasrequestparser:443}")
    private String requestParserUrl;

    @Value("${pagw.sync.businessValidatorUrl:http://pasbusinessvalidator:443}")
    private String businessValidatorUrl;
    
    @Value("${pagw.sync.enabled:true}")
    private boolean syncEnabled;
    
    @Value("${pagw.sync.timeoutSeconds:13}")
    private int syncTimeoutSeconds;
    
    public SyncProcessingService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            S3Service s3Service,
            RequestTrackerService requestTrackerService,
            PagwProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.s3Service = s3Service;
        this.requestTrackerService = requestTrackerService;
        this.properties = properties;
        // Single-threaded executor for sync processing to avoid thread explosion
        this.executor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2,
                r -> {
                    Thread t = new Thread(r, "sync-processing-thread");
                    t.setDaemon(true);
                    return t;
                }
        );
    }
    
    /**
     * Check if synchronous processing is enabled.
     */
    public boolean isSyncEnabled() {
        return syncEnabled;
    }
    
    /**
     * Process a PAS request synchronously through Parser → Validator → Decision.
     * 
     * @param pagwId The unique PAGW tracking ID
     * @param fhirBundle The FHIR Bundle containing the Claim
     * @param tenant The tenant/client identifier
     * @param authenticatedProviderId The provider ID from OAuth token
     * @return SyncProcessingResult with disposition or pended status
     */
    public SyncProcessingResult processSync(
            String pagwId, 
            String fhirBundle, 
            String tenant,
            String authenticatedProviderId) {
        
        long startTime = System.currentTimeMillis();
        log.info("Starting synchronous processing: pagwId={}, tenant={}", pagwId, tenant);
        
        // Wrap in a future for timeout handling
        Future<SyncProcessingResult> future = executor.submit(() -> 
                doSyncProcessing(pagwId, fhirBundle, tenant, authenticatedProviderId, startTime));
        
        try {
            int timeout = syncTimeoutSeconds > 0 ? syncTimeoutSeconds : SYNC_TIMEOUT_SECONDS;
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long processingTime = System.currentTimeMillis() - startTime;
            log.warn("Synchronous processing timed out: pagwId={}, timeMs={}", pagwId, processingTime);
            return SyncProcessingResult.pended("TIMEOUT", pagwId, processingTime);
        } catch (InterruptedException | ExecutionException e) {
            long processingTime = System.currentTimeMillis() - startTime;
            log.error("Synchronous processing failed: pagwId={}", pagwId, e);
            return SyncProcessingResult.builder()
                    .completed(false)
                    .valid(false)
                    .disposition(Disposition.ERROR)
                    .errorMessage("Processing error: " + e.getMessage())
                    .processingTimeMs(processingTime)
                    .processedAt(Instant.now())
                    .build();
        }
    }
    
    private SyncProcessingResult doSyncProcessing(
            String pagwId, 
            String fhirBundle, 
            String tenant,
            String authenticatedProviderId,
            long startTime) {
        
        try {
            // Step 1: Extract Bundle.identifier for idempotency check
            String bundleIdentifier = extractBundleIdentifier(fhirBundle);
            log.debug("Extracted Bundle.identifier: pagwId={}, bundleId={}", pagwId, bundleIdentifier);
            
            // Step 2: Extract provider identifier from Claim for security validation
            String claimProviderId = extractClaimProviderId(fhirBundle);
            
            // Step 3: Validate provider match (Da Vinci PAS requirement)
            if (authenticatedProviderId != null && claimProviderId != null) {
                if (!authenticatedProviderId.equals(claimProviderId)) {
                    log.warn("Provider mismatch: pagwId={}, claimProvider={}, tokenProvider={}", 
                            pagwId, claimProviderId, authenticatedProviderId);
                    return SyncProcessingResult.providerMismatch(claimProviderId, authenticatedProviderId);
                }
            }
            
            // Step 4: Call Request Parser synchronously
            ParserResponse parserResponse = callRequestParser(pagwId, fhirBundle, tenant);
            if (!parserResponse.success) {
                return SyncProcessingResult.validationError(
                        parserResponse.errors, 
                        System.currentTimeMillis() - startTime);
            }
            log.debug("Request parser completed: pagwId={}", pagwId);
            
            // Step 5: Call Business Validator synchronously
            ValidatorResponse validatorResponse = callBusinessValidator(
                    pagwId, parserResponse.parsedBundle, tenant);
            if (!validatorResponse.valid) {
                return SyncProcessingResult.validationError(
                        validatorResponse.errors, 
                        System.currentTimeMillis() - startTime);
            }
            log.debug("Business validator completed: pagwId={}", pagwId);
            
            // Step 6: Check if immediate decision is possible
            if (validatorResponse.canDecideImmediately) {
                // Build ClaimResponse for immediate decision
                String claimResponse = buildClaimResponse(
                        pagwId, 
                        bundleIdentifier,
                        validatorResponse.disposition,
                        validatorResponse.authorizationId,
                        validatorResponse.validUntil);
                
                long processingTime = System.currentTimeMillis() - startTime;
                
                // CRITICAL: Mark as sync processed to prevent async queueing race condition
                requestTrackerService.markSyncProcessed(pagwId);
                
                log.info("Synchronous processing completed with immediate decision: pagwId={}, disposition={}, timeMs={}", 
                        pagwId, validatorResponse.disposition, processingTime);
                
                return SyncProcessingResult.success(
                        validatorResponse.disposition, 
                        claimResponse, 
                        processingTime);
            } else {
                // Return pended - needs async processing
                long processingTime = System.currentTimeMillis() - startTime;
                log.info("Synchronous processing returning pended: pagwId={}, reason={}, timeMs={}", 
                        pagwId, validatorResponse.pendedReason, processingTime);
                
                return SyncProcessingResult.pended(
                        "BUSINESS_VALIDATOR",
                        pagwId,
                        processingTime);
            }
            
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            log.error("Synchronous processing error: pagwId={}", pagwId, e);
            return SyncProcessingResult.builder()
                    .completed(false)
                    .valid(false)
                    .disposition(Disposition.ERROR)
                    .errorMessage(e.getMessage())
                    .processingTimeMs(processingTime)
                    .processedAt(Instant.now())
                    .build();
        }
    }
    
    /**
     * Extract Bundle.identifier from FHIR Bundle.
     */
    private String extractBundleIdentifier(String fhirBundle) {
        try {
            JsonNode root = objectMapper.readTree(fhirBundle);
            JsonNode identifier = root.path("identifier");
            if (!identifier.isMissingNode() && identifier.has("value")) {
                return identifier.get("value").asText();
            }
            // If no identifier, generate one
            return "auto-" + UUID.randomUUID();
        } catch (JsonProcessingException e) {
            log.warn("Failed to extract Bundle.identifier", e);
            return "unknown-" + UUID.randomUUID();
        }
    }
    
    /**
     * Extract Claim.provider.identifier from FHIR Bundle.
     */
    private String extractClaimProviderId(String fhirBundle) {
        try {
            JsonNode root = objectMapper.readTree(fhirBundle);
            JsonNode entries = root.path("entry");
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    JsonNode resource = entry.path("resource");
                    if ("Claim".equals(resource.path("resourceType").asText())) {
                        JsonNode providerRef = resource.path("provider");
                        if (providerRef.has("identifier")) {
                            return providerRef.path("identifier").path("value").asText();
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to extract Claim.provider.identifier", e);
        }
        return null;
    }
    
    /**
     * Call Request Parser service synchronously.
     */
    private ParserResponse callRequestParser(String pagwId, String fhirBundle, String tenant) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-PAGW-ID", pagwId);
            headers.set("X-PAGW-Tenant", tenant);
            headers.set("X-PAGW-Sync", "true");
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("pagwId", pagwId);
            requestBody.put("tenant", tenant);
            requestBody.put("fhirBundle", fhirBundle);
            
            HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                    requestParserUrl + "/pas/v1/parse",
                    HttpMethod.POST,
                    request,
                    String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode responseBody = objectMapper.readTree(response.getBody());
                ParserResponse result = new ParserResponse();
                result.success = responseBody.path("success").asBoolean(true);
                result.parsedBundle = responseBody.path("parsedBundle").asText(fhirBundle);
                
                if (!result.success) {
                    result.errors = extractErrors(responseBody.path("errors"));
                }
                return result;
            } else {
                ParserResponse result = new ParserResponse();
                result.success = false;
                result.errors = List.of(ValidationError.builder()
                        .code("PARSER_ERROR")
                        .severity("error")
                        .message("Request parser returned: " + response.getStatusCode())
                        .issueType(ValidationError.ISSUE_STRUCTURE)
                        .build());
                return result;
            }
        } catch (RestClientException e) {
            log.warn("Request parser call failed: pagwId={}", pagwId, e);
            // Continue with original bundle if parser is unavailable
            ParserResponse result = new ParserResponse();
            result.success = true;
            result.parsedBundle = fhirBundle;
            return result;
        } catch (JsonProcessingException e) {
            ParserResponse result = new ParserResponse();
            result.success = false;
            result.errors = List.of(ValidationError.builder()
                    .code("PARSER_RESPONSE_ERROR")
                    .severity("error")
                    .message("Failed to parse parser response: " + e.getMessage())
                    .issueType(ValidationError.ISSUE_STRUCTURE)
                    .build());
            return result;
        }
    }
    
    /**
     * Call Business Validator service synchronously.
     */
    private ValidatorResponse callBusinessValidator(String pagwId, String parsedBundle, String tenant) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-PAGW-ID", pagwId);
            headers.set("X-PAGW-Tenant", tenant);
            headers.set("X-PAGW-Sync", "true");
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("pagwId", pagwId);
            requestBody.put("tenant", tenant);
            requestBody.put("fhirBundle", parsedBundle);
            
            HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                    businessValidatorUrl + "/pas/v1/validate",
                    HttpMethod.POST,
                    request,
                    String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode responseBody = objectMapper.readTree(response.getBody());
                ValidatorResponse result = new ValidatorResponse();
                result.valid = responseBody.path("valid").asBoolean(true);
                result.canDecideImmediately = responseBody.path("canDecideImmediately").asBoolean(false);
                
                if (result.canDecideImmediately) {
                    String dispCode = responseBody.path("disposition").asText("A4");
                    result.disposition = Disposition.fromX12Code(dispCode);
                    result.authorizationId = responseBody.path("authorizationId").asText();
                    result.validUntil = responseBody.path("validUntil").asText();
                } else {
                    result.disposition = Disposition.PENDED;
                    result.pendedReason = responseBody.path("pendedReason").asText("Additional review required");
                }
                
                if (!result.valid) {
                    result.errors = extractErrors(responseBody.path("errors"));
                }
                return result;
            } else {
                ValidatorResponse result = new ValidatorResponse();
                result.valid = false;
                result.errors = List.of(ValidationError.builder()
                        .code("VALIDATOR_ERROR")
                        .severity("error")
                        .message("Business validator returned: " + response.getStatusCode())
                        .issueType(ValidationError.ISSUE_BUSINESS_RULE)
                        .build());
                return result;
            }
        } catch (RestClientException e) {
            log.warn("Business validator call failed: pagwId={}", pagwId, e);
            // Return pended if validator is unavailable
            ValidatorResponse result = new ValidatorResponse();
            result.valid = true;
            result.canDecideImmediately = false;
            result.disposition = Disposition.PENDED;
            result.pendedReason = "Validator unavailable, queued for async processing";
            return result;
        } catch (JsonProcessingException e) {
            ValidatorResponse result = new ValidatorResponse();
            result.valid = false;
            result.errors = List.of(ValidationError.builder()
                    .code("VALIDATOR_RESPONSE_ERROR")
                    .severity("error")
                    .message("Failed to parse validator response: " + e.getMessage())
                    .issueType(ValidationError.ISSUE_STRUCTURE)
                    .build());
            return result;
        }
    }
    
    private List<ValidationError> extractErrors(JsonNode errorsNode) {
        List<ValidationError> errors = new ArrayList<>();
        if (errorsNode.isArray()) {
            for (JsonNode error : errorsNode) {
                errors.add(ValidationError.builder()
                        .code(error.path("code").asText("UNKNOWN"))
                        .severity(error.path("severity").asText("error"))
                        .location(error.path("location").asText())
                        .message(error.path("message").asText())
                        .issueType(error.path("issueType").asText(ValidationError.ISSUE_VALUE))
                        .build());
            }
        }
        return errors;
    }
    
    /**
     * Build FHIR ClaimResponse for immediate decisions.
     */
    private String buildClaimResponse(
            String pagwId,
            String bundleIdentifier,
            Disposition disposition,
            String authorizationId,
            String validUntil) {
        
        try {
            ObjectNode bundle = objectMapper.createObjectNode();
            bundle.put("resourceType", "Bundle");
            bundle.put("type", "collection");
            bundle.put("id", pagwId);
            
            // Bundle identifier
            ObjectNode identifier = bundle.putObject("identifier");
            identifier.put("system", "urn:pagw:bundle");
            identifier.put("value", bundleIdentifier);
            
            // Entry array
            ArrayNode entries = bundle.putArray("entry");
            
            // ClaimResponse resource
            ObjectNode entry = entries.addObject();
            entry.put("fullUrl", "urn:uuid:" + UUID.randomUUID());
            
            ObjectNode claimResponse = entry.putObject("resource");
            claimResponse.put("resourceType", "ClaimResponse");
            claimResponse.put("id", UUID.randomUUID().toString());
            claimResponse.put("status", "active");
            claimResponse.put("outcome", disposition.getFhirCode());
            
            // Pre-auth reference
            ObjectNode preAuthRef = claimResponse.putObject("preAuthRef");
            preAuthRef.put("value", authorizationId != null ? authorizationId : pagwId);
            
            // Pre-auth period (if approved)
            if (disposition == Disposition.APPROVED && validUntil != null) {
                ObjectNode preAuthPeriod = claimResponse.putObject("preAuthPeriod");
                preAuthPeriod.put("start", Instant.now().toString());
                preAuthPeriod.put("end", validUntil);
            }
            
            // Add extension for disposition code
            ArrayNode extensions = claimResponse.putArray("extension");
            ObjectNode dispositionExt = extensions.addObject();
            dispositionExt.put("url", "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewAction");
            ObjectNode reviewCoding = dispositionExt.putObject("valueCodeableConcept").putObject("coding").putObject("coding");
            reviewCoding.put("system", "https://codesystem.x12.org/005010/306");
            reviewCoding.put("code", disposition.getX12Code());
            reviewCoding.put("display", disposition.getDescription());
            
            // Process note for pended
            if (disposition == Disposition.PENDED) {
                ArrayNode processNotes = claimResponse.putArray("processNote");
                ObjectNode note = processNotes.addObject();
                note.put("number", 1);
                note.put("text", "This request requires additional review. You will be notified when a decision is made. " +
                        "Use $inquiry operation with tracking ID: " + pagwId);
            }
            
            return objectMapper.writeValueAsString(bundle);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to build ClaimResponse", e);
            return "{\"error\": \"Failed to build response\"}";
        }
    }
    
    // Internal response classes
    private static class ParserResponse {
        boolean success;
        String parsedBundle;
        List<ValidationError> errors = new ArrayList<>();
    }
    
    private static class ValidatorResponse {
        boolean valid;
        boolean canDecideImmediately;
        Disposition disposition;
        String authorizationId;
        String validUntil;
        String pendedReason;
        List<ValidationError> errors = new ArrayList<>();
    }
}
