package com.anthem.pagw.core.model.fhir;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Extracted practitioner information from FHIR Bundle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PractitionerInfo {
    private String npi;                // National Provider Identifier
    private String firstName;
    private String lastName;
    private String taxonomyCode;       // Healthcare Provider Taxonomy
    private String specialty;
    private String phone;
    private String email;
}
