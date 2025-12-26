package com.anthem.pagw.core.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Input context for the Decision Engine.
 * Contains all factors needed for routing, auto-approval, and urgency decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionRequest {

    // Request identifiers
    private String pagwId;
    private String tenant;
    private String requestType;  // SUBMIT, INQUIRY, UPDATE, CANCEL
    
    // Provider information
    private String providerId;
    private String providerNpi;
    private String providerTaxId;
    private String providerType;  // PHYSICIAN, FACILITY, DME, etc.
    private boolean isGoldCardProvider;
    private Double providerApprovalRate;  // Historical approval rate (0.0-1.0)
    
    // Member information
    private String memberId;
    private String subscriberId;
    private String planId;
    private String planType;  // HMO, PPO, EPO, POS
    private String lineOfBusiness;  // COMMERCIAL, MEDICARE, MEDICAID
    
    // Service details
    private String serviceTypeCode;  // X12 UM04 service type
    private String placeOfServiceCode;  // X12 UM07 place of service
    private String levelOfServiceCode;
    private List<String> cptCodes;
    private List<String> icd10Codes;
    private String primaryDiagnosisCode;
    
    // Request specifics
    private LocalDate serviceStartDate;
    private LocalDate serviceEndDate;
    private Integer requestedUnits;
    private Double estimatedCost;
    private boolean isUrgent;
    private boolean isRetroactive;
    
    // Payer routing hints
    private String preferredPayerId;
    private String delegatedPayerId;
    
    // Metadata
    private Map<String, Object> additionalFactors;
    
    /**
     * Service type codes that indicate urgent requests.
     */
    public static final List<String> URGENT_SERVICE_TYPES = List.of(
            "2",   // Urgent Care
            "4",   // Emergency
            "7",   // Inpatient Emergency
            "73"   // Ambulance Emergency
    );
    
    /**
     * Check if this is an urgent request based on service type.
     */
    public boolean isUrgentByServiceType() {
        return serviceTypeCode != null && URGENT_SERVICE_TYPES.contains(serviceTypeCode);
    }
}
