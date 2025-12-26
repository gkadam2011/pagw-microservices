package com.anthem.pagw.core.model.fhir;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ICD-10 diagnosis code from FHIR Claim
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisCode {
    private String code;               // ICD-10 code (e.g., "E11.9")
    private String display;            // Human-readable description
    private String system;             // http://hl7.org/fhir/sid/icd-10
    private int sequence;              // Position (1=primary, 2=secondary, etc.)
    private String type;               // principal, admitting, discharge
}
