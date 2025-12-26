package com.anthem.pagw.core.model.fhir;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Extracted patient information from FHIR Bundle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientInfo {
    private String memberId;           // From Patient.identifier (member ID system)
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private String gender;             // male, female, other, unknown
    private String addressLine1;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String phone;
    private String email;
}
