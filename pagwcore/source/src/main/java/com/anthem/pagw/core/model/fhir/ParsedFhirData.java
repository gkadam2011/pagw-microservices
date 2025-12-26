package com.anthem.pagw.core.model.fhir;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Container for all extracted FHIR data from a bundle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedFhirData {
    private String pagwId;
    private String tenant;
    private Instant parsedAt;
    
    // Core resources
    private PatientInfo patient;
    private PractitionerInfo practitioner;
    private ClaimInfo claim;
    
    // Statistics
    private int totalDiagnosisCodes;
    private int totalProcedureCodes;
    private int totalAttachments;
    private boolean hasUrgentIndicator;
    
    // S3 references
    private String rawBundleS3Path;
    private String parsedDataS3Path;
}
