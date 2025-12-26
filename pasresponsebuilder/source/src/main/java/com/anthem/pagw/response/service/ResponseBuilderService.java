package com.anthem.pagw.response.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.response.model.ClaimResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for building FHIR ClaimResponse resources.
 */
@Service
public class ResponseBuilderService {

    private static final Logger log = LoggerFactory.getLogger(ResponseBuilderService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Build a success ClaimResponse.
     * 
     * @param responseData API response data
     * @param message The PAGW message context
     * @return FHIR ClaimResponse
     */
    public ClaimResponse buildSuccessResponse(String responseData, PagwMessage message) {
        ClaimResponse response = createBaseResponse(message);
        
        // Default to approved per Da Vinci PAS
        response.setOutcome("approved");
        response.setDisposition("Certified");
        
        if (responseData != null) {
            try {
                JsonNode apiResponse = JsonUtils.parseJson(responseData);
                
                // Set external reference if available
                String externalId = apiResponse.path("externalId").asText();
                if (!externalId.isEmpty()) {
                    response.setPreAuthRef(externalId);
                }
                
                // Map API status to Da Vinci PAS outcomes
                String apiStatus = apiResponse.path("status").asText();
                switch (apiStatus) {
                    case "ACCEPTED", "APPROVED" -> {
                        response.setOutcome("approved");
                        response.setDisposition("Certified");
                    }
                    case "DENIED" -> {
                        response.setOutcome("denied");
                        response.setDisposition("Not Certified");
                    }
                    case "PENDING", "PENDED" -> {
                        response.setOutcome("pended");
                        response.setDisposition("Pended");
                    }
                    case "PARTIAL" -> {
                        response.setOutcome("partial");
                        response.setDisposition("Partially Certified");
                    }
                    case "MODIFIED" -> {
                        response.setOutcome("approved");
                        response.setDisposition("Certified with Modifications");
                    }
                }
                
            } catch (Exception e) {
                log.warn("Could not parse API response: pagwId={}", message.getPagwId());
            }
        }
        
        // Add processing note
        response.addProcessNote(1, "Prior authorization request processed", "display");
        response.addProcessNote(2, "PAGW ID: " + message.getPagwId(), "print");
        
        return response;
    }

    /**
     * Build an error ClaimResponse.
     * 
     * @param message The PAGW message context with error information
     * @return FHIR ClaimResponse with error details
     */
    public ClaimResponse buildErrorResponse(PagwMessage message) {
        ClaimResponse response = createBaseResponse(message);
        
        response.setOutcome("denied");
        response.setDisposition("Not Certified - Processing Error");
        
        // Add error details
        ClaimResponse.ClaimError error = new ClaimResponse.ClaimError();
        error.setCode(message.getErrorCode() != null ? message.getErrorCode() : "PROCESSING_ERROR");
        error.setMessage(message.getErrorMessage() != null ? message.getErrorMessage() : "An error occurred during processing");
        error.setSeverity("error");
        
        response.setErrors(List.of(error));
        
        // Add process note about the error
        response.addProcessNote(1, "Error: " + error.getMessage(), "display");
        response.addProcessNote(2, "Error Code: " + error.getCode(), "print");
        response.addProcessNote(3, "PAGW ID: " + message.getPagwId(), "print");
        
        return response;
    }

    private ClaimResponse createBaseResponse(PagwMessage message) {
        ClaimResponse response = new ClaimResponse();
        
        // FHIR resource metadata
        response.setResourceType("ClaimResponse");
        response.setId(UUID.randomUUID().toString());
        
        // Identifiers
        ClaimResponse.Identifier identifier = new ClaimResponse.Identifier();
        identifier.setSystem("urn:anthem:pagw:claim-response");
        identifier.setValue(message.getPagwId());
        response.setIdentifier(List.of(identifier));
        
        // Status
        response.setStatus("active");
        response.setUse("claim");
        
        // Timestamps
        response.setCreated(ISO_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC)));
        
        // References
        response.setRequestReference("Claim/" + message.getPagwId());
        
        // Patient reference (from metadata if available)
        if (message.getMetadata() != null && message.getMetadata().containsKey("patientReference")) {
            response.setPatientReference(message.getMetadata().get("patientReference").toString());
        }
        
        // Insurer
        response.setInsurerReference("Organization/anthem");
        response.setInsurerDisplay("Anthem Blue Cross Blue Shield");
        
        // Initialize process notes
        response.setProcessNote(new ArrayList<>());
        
        return response;
    }
}
