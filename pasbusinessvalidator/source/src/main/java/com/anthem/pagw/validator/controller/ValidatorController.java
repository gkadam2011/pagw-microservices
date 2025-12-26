package com.anthem.pagw.validator.controller;

import com.anthem.pagw.validator.model.ValidationResult;
import com.anthem.pagw.validator.service.BusinessValidatorService;
import com.anthem.pagw.core.model.PagwMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for Business Validator - supports synchronous validation calls.
 * 
 * This controller provides HTTP endpoints for orchestrator's synchronous path.
 * Primary flow is via SQS (BusinessValidatorListener), but this enables
 * 15-second response requirement for Da Vinci PAS compliance.
 */
@RestController
@RequestMapping("/pas/api/v1")
public class ValidatorController {

    private static final Logger log = LoggerFactory.getLogger(ValidatorController.class);
    
    private final BusinessValidatorService validatorService;
    private final ObjectMapper objectMapper;
    
    public ValidatorController(BusinessValidatorService validatorService, ObjectMapper objectMapper) {
        this.validatorService = validatorService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Validate FHIR bundle synchronously.
     * 
     * Expected request body:
     * {
     *   "pagwId": "PAGW-xxx",
     *   "tenant": "tenant-id",
     *   "fhirBundle": "{ ... FHIR JSON ... }"
     * }
     * 
     * @param requestBody The validation request
     * @return ValidationResult with validation errors/warnings
     */
    @PostMapping(
            value = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> validate(@RequestBody String requestBody) {
        try {
            log.info("Received sync validation request");
            
            // Parse request
            JsonNode request = objectMapper.readTree(requestBody);
            String pagwId = request.path("pagwId").asText();
            String tenant = request.path("tenant").asText();
            String fhirBundle = request.path("fhirBundle").asText();
            
            log.info("Validating bundle: pagwId={}, tenant={}", pagwId, tenant);
            
            // Create message context
            PagwMessage message = PagwMessage.builder()
                    .pagwId(pagwId)
                    .tenant(tenant)
                    .stage("BUSINESS_VALIDATOR")
                    .build();
            
            // Perform validation
            ValidationResult result = validatorService.validate(fhirBundle, message);
            
            // Build response matching orchestrator's expectations
            Map<String, Object> response = new HashMap<>();
            response.put("valid", result.isValid());
            response.put("canDecideImmediately", false); // Most validations need async payer check
            response.put("disposition", "A4"); // Pended - additional information required
            response.put("pendedReason", result.isValid() 
                    ? "Validation passed, awaiting payer decision" 
                    : "Validation errors found, manual review required");
            
            if (!result.isValid()) {
                response.put("errors", result.getErrors());
            }
            if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
                response.put("warnings", result.getWarnings());
            }
            
            log.info("Validation completed: pagwId={}, valid={}, errors={}, warnings={}", 
                    pagwId, result.isValid(), 
                    result.getErrors() != null ? result.getErrors().size() : 0,
                    result.getWarnings() != null ? result.getWarnings().size() : 0);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Validation failed", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("valid", false);
            errorResponse.put("canDecideImmediately", false);
            errorResponse.put("disposition", "A4");
            errorResponse.put("pendedReason", "Validation error: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "pasbusinessvalidator"));
    }
}
