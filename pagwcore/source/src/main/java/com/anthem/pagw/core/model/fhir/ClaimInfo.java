package com.anthem.pagw.core.model.fhir;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracted claim information from FHIR Bundle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimInfo {
    private String claimId;            // From Claim.identifier
    private String bundleId;           // From Bundle.identifier
    private LocalDate serviceDate;
    private String claimType;          // professional, institutional, pharmacy
    private String billablePeriodStart;
    private String billablePeriodEnd;
    
    // Clinical data
    @Builder.Default
    private List<DiagnosisCode> diagnosisCodes = new ArrayList<>();
    
    @Builder.Default
    private List<ProcedureCode> procedureCodes = new ArrayList<>();
    
    // Financial
    private BigDecimal totalAmount;
    private String currency;           // USD
    
    // Supporting data
    private Integer attachmentCount;
    private String priority;           // stat, normal, deferred
    private String use;                // claim, preauthorization, predetermination
}
