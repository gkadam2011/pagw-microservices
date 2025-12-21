package com.anthem.pagw.orchestrator.model;

import com.anthem.pagw.orchestrator.model.SyncProcessingResult.Disposition;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Inquiry Response model for Da Vinci PAS $inquiry operation.
 * 
 * Returns the current status of a prior authorization request.
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/OperationDefinition-Claim-inquiry.html">$inquiry</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InquiryResponse {
    
    /**
     * PAGW internal tracking ID.
     */
    private String pagwId;
    
    /**
     * Payer-assigned authorization reference.
     */
    private String preAuthRef;
    
    /**
     * Current internal status.
     */
    private String status;
    
    /**
     * Da Vinci PAS disposition.
     */
    private Disposition disposition;
    
    /**
     * Human-readable disposition description.
     */
    private String dispositionDescription;
    
    /**
     * Current processing stage.
     */
    private String currentStage;
    
    /**
     * Progress percentage (0-100) for in-progress requests.
     */
    private Integer progressPercentage;
    
    /**
     * Estimated completion time for pended requests.
     */
    private Instant estimatedCompletionTime;
    
    /**
     * Timestamp when request was received.
     */
    private Instant receivedAt;
    
    /**
     * Timestamp of last update.
     */
    private Instant updatedAt;
    
    /**
     * Timestamp when completed (if applicable).
     */
    private Instant completedAt;
    
    /**
     * Full FHIR ClaimResponse bundle (if completed).
     */
    private String claimResponseBundle;
    
    /**
     * Error code (if failed).
     */
    private String errorCode;
    
    /**
     * Error message (if failed).
     */
    private String errorMessage;
    
    /**
     * Whether the authorization was found.
     */
    private boolean found;
    
    /**
     * Message for not found responses.
     */
    private String message;
    
    /**
     * Create a "not found" response.
     */
    public static InquiryResponse notFound(InquiryRequest request) {
        StringBuilder searchCriteria = new StringBuilder();
        if (request.getPagwId() != null) {
            searchCriteria.append("pagwId=").append(request.getPagwId());
        } else if (request.getPreAuthRef() != null) {
            searchCriteria.append("preAuthRef=").append(request.getPreAuthRef());
        } else if (request.getBundleIdentifier() != null) {
            searchCriteria.append("bundleId=").append(request.getBundleIdentifier());
        } else if (request.getPatientId() != null) {
            searchCriteria.append("patient=").append(request.getPatientId())
                    .append(", provider=").append(request.getProviderId());
        }
        
        return InquiryResponse.builder()
                .found(false)
                .disposition(Disposition.ERROR)
                .dispositionDescription("Not Found")
                .message("No prior authorization found matching criteria: " + searchCriteria)
                .build();
    }
}
