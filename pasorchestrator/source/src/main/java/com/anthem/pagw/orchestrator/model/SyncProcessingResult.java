package com.anthem.pagw.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of synchronous processing through the Da Vinci PAS pipeline.
 * Per Da Vinci PAS IG, the entire request-response cycle must complete in ≤15 seconds.
 * 
 * If processing cannot complete in time, this result will indicate "PENDED" disposition
 * and the request continues via async queue path.
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/specification.html">Da Vinci PAS IG</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SyncProcessingResult {
    
    /**
     * Whether synchronous processing completed successfully.
     * If false, the request was queued for async processing and a pended response should be returned.
     */
    private boolean completed;
    
    /**
     * The disposition of the prior authorization request.
     * Maps to X12 271 response codes and Da Vinci PAS ClaimResponse.
     */
    private Disposition disposition;
    
    /**
     * Stage where processing stopped (if not completed synchronously).
     */
    private String lastStage;
    
    /**
     * Processing time in milliseconds.
     */
    private long processingTimeMs;
    
    /**
     * The final FHIR ClaimResponse bundle (if completed).
     */
    private String claimResponseBundle;
    
    /**
     * Payer reference ID for tracking (maps to ClaimResponse.identifier).
     */
    private String payerTrackingId;
    
    /**
     * Validation errors encountered during processing.
     */
    @Builder.Default
    private List<ValidationError> validationErrors = new ArrayList<>();
    
    /**
     * Whether the request was validated successfully.
     */
    private boolean valid;
    
    /**
     * Error message if processing failed.
     */
    private String errorMessage;
    
    /**
     * Timestamp of when processing completed.
     */
    private Instant processedAt;
    
    /**
     * Whether provider identifier matched the OAuth token.
     * Required per Da Vinci PAS IG 4.0.6 Provider Match.
     */
    private boolean providerMatched;
    
    /**
     * Whether this is a duplicate submission.
     * Da Vinci PAS requires Bundle.identifier uniqueness.
     */
    private boolean duplicate;
    
    /**
     * The original Bundle.identifier for tracking.
     */
    private String bundleIdentifier;
    
    /**
     * Prior Authorization disposition codes per Da Vinci PAS.
     * Maps to X12 271 HCR01 codes.
     */
    public enum Disposition {
        /**
         * A1 - Certified in Total - The request was approved in full.
         */
        APPROVED("A1", "approved", "Certified in Total"),
        
        /**
         * A2 - Certified - Partial - Only some of the requested items/services were approved.
         */
        PARTIAL("A2", "partial", "Certified - Partial"),
        
        /**
         * A3 - Not Certified - The request was denied.
         */
        DENIED("A3", "denied", "Not Certified"),
        
        /**
         * A4 - Pended - A decision cannot be made at this time; additional information may be needed.
         */
        PENDED("A4", "pended", "Pended"),
        
        /**
         * A6 - Modified - The request was approved with modifications.
         */
        MODIFIED("A6", "modified", "Modified"),
        
        /**
         * Cancelled - The prior authorization was cancelled.
         */
        CANCELLED("X", "cancelled", "Cancelled"),
        
        /**
         * Error - Processing error occurred.
         */
        ERROR("E", "error", "Error");
        
        private final String x12Code;
        private final String fhirCode;
        private final String description;
        
        Disposition(String x12Code, String fhirCode, String description) {
            this.x12Code = x12Code;
            this.fhirCode = fhirCode;
            this.description = description;
        }
        
        public String getX12Code() { return x12Code; }
        public String getFhirCode() { return fhirCode; }
        public String getDescription() { return description; }
        
        /**
         * Map X12 271 HCR01 code to Disposition.
         */
        public static Disposition fromX12Code(String code) {
            for (Disposition d : values()) {
                if (d.x12Code.equals(code)) {
                    return d;
                }
            }
            return PENDED; // Default to pended for unknown codes
        }
        
        /**
         * Map FHIR ClaimResponse.outcome to Disposition.
         */
        public static Disposition fromFhirOutcome(String outcome) {
            for (Disposition d : values()) {
                if (d.fhirCode.equals(outcome)) {
                    return d;
                }
            }
            return PENDED;
        }
    }
    
    /**
     * Validation error detail.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationError {
        private String code;
        private String severity; // error, warning, information
        private String location; // FHIRPath or element reference
        private String message;
        private String issueType; // OperationOutcome issue type
        
        // Common issue types per Da Vinci PAS
        public static final String ISSUE_STRUCTURE = "structure";
        public static final String ISSUE_REQUIRED = "required";
        public static final String ISSUE_VALUE = "value";
        public static final String ISSUE_BUSINESS_RULE = "business-rule";
        public static final String ISSUE_SECURITY = "security";
        public static final String ISSUE_DUPLICATE = "duplicate";
        public static final String ISSUE_TIMEOUT = "timeout";
    }
    
    /**
     * Create a successful completion result.
     */
    public static SyncProcessingResult success(Disposition disposition, String claimResponseBundle, long processingTimeMs) {
        return SyncProcessingResult.builder()
                .completed(true)
                .valid(true)
                .disposition(disposition)
                .claimResponseBundle(claimResponseBundle)
                .processingTimeMs(processingTimeMs)
                .processedAt(Instant.now())
                .build();
    }
    
    /**
     * Create a pended result when async processing is needed.
     */
    public static SyncProcessingResult pended(String lastStage, String payerTrackingId, long processingTimeMs) {
        return SyncProcessingResult.builder()
                .completed(false)
                .valid(true)
                .disposition(Disposition.PENDED)
                .lastStage(lastStage)
                .payerTrackingId(payerTrackingId)
                .processingTimeMs(processingTimeMs)
                .processedAt(Instant.now())
                .build();
    }
    
    /**
     * Create a validation error result.
     */
    public static SyncProcessingResult validationError(List<ValidationError> errors, long processingTimeMs) {
        return SyncProcessingResult.builder()
                .completed(true)
                .valid(false)
                .disposition(Disposition.ERROR)
                .validationErrors(errors)
                .processingTimeMs(processingTimeMs)
                .processedAt(Instant.now())
                .build();
    }
    
    /**
     * Create a duplicate rejection result.
     */
    public static SyncProcessingResult duplicate(String bundleIdentifier) {
        return SyncProcessingResult.builder()
                .completed(true)
                .valid(false)
                .duplicate(true)
                .bundleIdentifier(bundleIdentifier)
                .disposition(Disposition.ERROR)
                .errorMessage("Duplicate Bundle.identifier: " + bundleIdentifier)
                .processingTimeMs(0)
                .processedAt(Instant.now())
                .validationErrors(List.of(
                        ValidationError.builder()
                                .code("DUPLICATE_BUNDLE_ID")
                                .severity("error")
                                .location("Bundle.identifier")
                                .message("A submission with this Bundle.identifier already exists")
                                .issueType(ValidationError.ISSUE_DUPLICATE)
                                .build()
                ))
                .build();
    }
    
    /**
     * Create a provider mismatch error result.
     */
    public static SyncProcessingResult providerMismatch(String providerId, String tokenProviderId) {
        return SyncProcessingResult.builder()
                .completed(true)
                .valid(false)
                .providerMatched(false)
                .disposition(Disposition.ERROR)
                .errorMessage("Provider identifier mismatch")
                .processingTimeMs(0)
                .processedAt(Instant.now())
                .validationErrors(List.of(
                        ValidationError.builder()
                                .code("PROVIDER_MISMATCH")
                                .severity("error")
                                .location("Claim.provider.identifier")
                                .message(String.format("Claim.provider.identifier (%s) does not match authenticated provider (%s)", 
                                        providerId, tokenProviderId))
                                .issueType(ValidationError.ISSUE_SECURITY)
                                .build()
                ))
                .build();
    }
}
