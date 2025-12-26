package com.anthem.pagw.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * PAS Response model - FHIR ClaimResponse or OperationOutcome.
 * 
 * Per Da Vinci PAS IG, responses include:
 * - Immediate decisions (approved, denied, partial, modified)
 * - Pended status with subscription info
 * - OperationOutcome for validation errors
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PasResponse {
    
    /**
     * FHIR resource type: ClaimResponse or OperationOutcome
     */
    private String resourceType;
    
    /**
     * Internal PAGW tracking ID.
     */
    private String pagwId;
    
    /**
     * Response status (approved, denied, partial, pended, queued, error).
     */
    private String status;
    
    /**
     * Current processing stage.
     */
    private String stage;
    
    /**
     * Human-readable message.
     */
    private String message;
    
    /**
     * Response timestamp.
     */
    private Instant timestamp;
    
    /**
     * Prior authorization disposition (Certified, Not Certified, Pended, etc).
     */
    private String disposition;
    
    /**
     * Payer's tracking ID for the authorization.
     * Maps to ClaimResponse.preAuthRef.
     */
    private String payerTrackingId;
    
    /**
     * Complete FHIR ClaimResponse bundle for synchronous responses.
     */
    private String claimResponseBundle;
    
    /**
     * Processing time in milliseconds.
     */
    private Long processingTimeMs;
    
    /**
     * Authorization period start (for approved requests).
     */
    private Instant authorizationStart;
    
    /**
     * Authorization period end (for approved requests).
     */
    private Instant authorizationEnd;
    
    // Error details
    private String errorCode;
    private String errorMessage;
    
    /**
     * Validation errors (for OperationOutcome responses).
     */
    private List<SyncProcessingResult.ValidationError> validationErrors;
    
    /**
     * Subscription info for pended responses.
     * Provider can subscribe to this topic for updates.
     */
    private String subscriptionTopic;
}
