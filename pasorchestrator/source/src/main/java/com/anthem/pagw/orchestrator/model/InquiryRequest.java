package com.anthem.pagw.orchestrator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Inquiry Request model for Da Vinci PAS $inquiry operation.
 * 
 * Providers can query by:
 * - PAGW tracking ID (pagwId)
 * - Payer tracking ID (preAuthRef) 
 * - Original Bundle.identifier
 * - Patient + Provider + Service Date combination
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/OperationDefinition-Claim-inquiry.html">$inquiry</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InquiryRequest {
    
    /**
     * PAGW internal tracking ID.
     */
    private String pagwId;
    
    /**
     * Payer-assigned authorization reference (ClaimResponse.preAuthRef).
     */
    private String preAuthRef;
    
    /**
     * Original Bundle.identifier from submission.
     */
    private String bundleIdentifier;
    
    /**
     * Patient identifier for search.
     */
    private String patientId;
    
    /**
     * Provider identifier for search.
     */
    private String providerId;
    
    /**
     * Service date range start.
     */
    private LocalDate serviceDateFrom;
    
    /**
     * Service date range end.
     */
    private LocalDate serviceDateTo;
    
    /**
     * Tenant identifier.
     */
    private String tenant;
    
    /**
     * Correlation ID for tracing.
     */
    private String correlationId;
    
    /**
     * Raw FHIR Bundle (if submitted as $inquiry operation).
     */
    private String fhirBundle;
    
    /**
     * Authenticated provider ID from token.
     */
    private String authenticatedProviderId;
}
