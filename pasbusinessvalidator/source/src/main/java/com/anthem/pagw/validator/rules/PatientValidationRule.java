package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.validator.model.ValidationError;
import com.anthem.pagw.validator.model.ValidationWarning;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates patient/member information.
 */
@Component
public class PatientValidationRule implements ValidationRule {

    // Member ID systems
    private static final Set<String> MEMBER_ID_SYSTEMS = Set.of(
            "http://example.org/fhir/identifier/member",
            "urn:oid:2.16.840.1.113883.4.3",
            "member",
            "subscriber"
    );
    
    // Date pattern: YYYY-MM-DD
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    
    // Valid genders
    private static final Set<String> VALID_GENDERS = Set.of("male", "female", "other", "unknown");
    
    // State codes (US)
    private static final Set<String> US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            "DC", "PR", "VI", "GU", "AS", "MP"
    );

    @Override
    public boolean isApplicable(JsonNode data, PagwMessage message) {
        return data.has("patientData");
    }

    @Override
    public RuleResult validate(JsonNode data, PagwMessage message) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        JsonNode patient = data.path("patientData");
        
        if (patient.isMissingNode()) {
            errors.add(new ValidationError(
                    "MISSING_PATIENT",
                    "Patient data is required for prior authorization",
                    "patientData"
            ));
            return new RuleResult(errors, warnings);
        }
        
        // Validate member ID
        validateMemberId(patient, errors, warnings);
        
        // Validate name
        validateName(patient, errors);
        
        // Validate date of birth
        validateDateOfBirth(patient, errors);
        
        // Validate gender
        validateGender(patient, warnings);
        
        // Validate address
        validateAddress(patient, warnings);
        
        return new RuleResult(errors, warnings);
    }

    private void validateMemberId(JsonNode patient, List<ValidationError> errors, List<ValidationWarning> warnings) {
        JsonNode identifiers = patient.path("identifier");
        
        if (!identifiers.isArray() || identifiers.isEmpty()) {
            errors.add(new ValidationError(
                    "MISSING_PATIENT_IDENTIFIER",
                    "Patient must have at least one identifier (member ID)",
                    "patientData.identifier"
            ));
            return;
        }
        
        boolean hasMemberId = false;
        
        for (JsonNode id : identifiers) {
            String system = id.path("system").asText().toLowerCase();
            String value = id.path("value").asText();
            
            // Check if this is a member ID system
            boolean isMemberSystem = MEMBER_ID_SYSTEMS.stream()
                    .anyMatch(s -> system.contains(s.toLowerCase()));
            
            if (isMemberSystem) {
                hasMemberId = true;
                
                // Validate member ID format (basic length check)
                if (value.isEmpty()) {
                    errors.add(new ValidationError(
                            "EMPTY_MEMBER_ID",
                            "Member ID value cannot be empty",
                            "patientData.identifier"
                    ));
                } else if (value.length() < 5 || value.length() > 30) {
                    warnings.add(new ValidationWarning(
                            "UNUSUAL_MEMBER_ID_LENGTH",
                            String.format("Member ID length (%d) is unusual - verify correctness", value.length()),
                            "patientData.identifier"
                    ));
                }
            }
        }
        
        if (!hasMemberId) {
            warnings.add(new ValidationWarning(
                    "NO_MEMBER_ID_SYSTEM",
                    "No identifier found with recognized member ID system",
                    "patientData.identifier"
            ));
        }
    }

    private void validateName(JsonNode patient, List<ValidationError> errors) {
        JsonNode name = patient.path("name");
        
        if (name.isMissingNode() || (name.isArray() && name.isEmpty())) {
            errors.add(new ValidationError(
                    "MISSING_PATIENT_NAME",
                    "Patient name is required",
                    "patientData.name"
            ));
            return;
        }
        
        // Get first name entry
        JsonNode nameEntry = name.isArray() ? name.path(0) : name;
        
        String family = nameEntry.path("family").asText();
        String given = nameEntry.path("given").asText();
        
        if (family.isEmpty()) {
            errors.add(new ValidationError(
                    "MISSING_FAMILY_NAME",
                    "Patient family name (last name) is required",
                    "patientData.name.family"
            ));
        }
        
        if (given.isEmpty()) {
            errors.add(new ValidationError(
                    "MISSING_GIVEN_NAME",
                    "Patient given name (first name) is required",
                    "patientData.name.given"
            ));
        }
    }

    private void validateDateOfBirth(JsonNode patient, List<ValidationError> errors) {
        String dob = patient.path("birthDate").asText();
        
        if (dob.isEmpty()) {
            errors.add(new ValidationError(
                    "MISSING_DOB",
                    "Patient date of birth is required",
                    "patientData.birthDate"
            ));
            return;
        }
        
        if (!DATE_PATTERN.matcher(dob).matches()) {
            errors.add(new ValidationError(
                    "INVALID_DOB_FORMAT",
                    String.format("Invalid date of birth format: %s. Expected YYYY-MM-DD", dob),
                    "patientData.birthDate"
            ));
            return;
        }
        
        // Validate it's a reasonable DOB (not in future, not too old)
        try {
            java.time.LocalDate dobDate = java.time.LocalDate.parse(dob);
            java.time.LocalDate today = java.time.LocalDate.now();
            
            if (dobDate.isAfter(today)) {
                errors.add(new ValidationError(
                        "FUTURE_DOB",
                        "Date of birth cannot be in the future",
                        "patientData.birthDate"
                ));
            }
            
            if (dobDate.isBefore(today.minusYears(130))) {
                errors.add(new ValidationError(
                        "INVALID_DOB",
                        "Date of birth indicates patient age over 130 years",
                        "patientData.birthDate"
                ));
            }
        } catch (Exception e) {
            errors.add(new ValidationError(
                    "INVALID_DOB",
                    "Cannot parse date of birth: " + dob,
                    "patientData.birthDate"
            ));
        }
    }

    private void validateGender(JsonNode patient, List<ValidationWarning> warnings) {
        String gender = patient.path("gender").asText();
        
        if (gender.isEmpty()) {
            warnings.add(new ValidationWarning(
                    "MISSING_GENDER",
                    "Patient gender is recommended",
                    "patientData.gender"
            ));
        } else if (!VALID_GENDERS.contains(gender.toLowerCase())) {
            warnings.add(new ValidationWarning(
                    "INVALID_GENDER",
                    String.format("Unknown gender value: %s. Expected: male, female, other, unknown", gender),
                    "patientData.gender"
            ));
        }
    }

    private void validateAddress(JsonNode patient, List<ValidationWarning> warnings) {
        JsonNode address = patient.path("address");
        
        if (address.isMissingNode() || (address.isArray() && address.isEmpty())) {
            warnings.add(new ValidationWarning(
                    "MISSING_ADDRESS",
                    "Patient address is recommended",
                    "patientData.address"
            ));
            return;
        }
        
        // Get first address
        JsonNode addr = address.isArray() ? address.path(0) : address;
        
        String state = addr.path("state").asText();
        if (!state.isEmpty() && !US_STATE_CODES.contains(state.toUpperCase())) {
            warnings.add(new ValidationWarning(
                    "UNKNOWN_STATE",
                    String.format("Unknown state code: %s", state),
                    "patientData.address.state"
            ));
        }
        
        String postalCode = addr.path("postalCode").asText();
        if (!postalCode.isEmpty() && !postalCode.matches("^\\d{5}(-\\d{4})?$")) {
            warnings.add(new ValidationWarning(
                    "INVALID_POSTAL_CODE",
                    String.format("Invalid US postal code format: %s", postalCode),
                    "patientData.address.postalCode"
            ));
        }
    }

    @Override
    public String getName() {
        return "PATIENT_VALIDATION";
    }

    @Override
    public int getPriority() {
        return 95; // High priority - patient data is critical
    }
}
