package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.validator.model.ValidationError;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates NPI (National Provider Identifier) numbers.
 */
@Component
public class NpiValidationRule implements ValidationRule {

    private static final Set<String> NPI_SYSTEMS = Set.of(
            "http://hl7.org/fhir/sid/us-npi",
            "urn:oid:2.16.840.1.113883.4.6"
    );

    @Override
    public boolean isApplicable(JsonNode data, PagwMessage message) {
        return data.has("practitionerData") || data.has("organizationData");
    }

    @Override
    public RuleResult validate(JsonNode data, PagwMessage message) {
        List<ValidationError> errors = new ArrayList<>();
        
        // Validate practitioner NPI
        if (data.has("practitionerData")) {
            JsonNode identifiers = data.path("practitionerData").path("identifier");
            validateNpi(identifiers, "practitioner", errors);
        }
        
        // Validate organization NPI
        if (data.has("organizationData")) {
            JsonNode identifiers = data.path("organizationData").path("identifier");
            validateNpi(identifiers, "organization", errors);
        }
        
        return new RuleResult(errors, List.of());
    }

    private void validateNpi(JsonNode identifiers, String entityType, List<ValidationError> errors) {
        if (identifiers.isArray()) {
            boolean hasNpi = false;
            for (JsonNode id : identifiers) {
                String system = id.path("system").asText();
                if (NPI_SYSTEMS.contains(system)) {
                    hasNpi = true;
                    String value = id.path("value").asText();
                    if (!isValidNpi(value)) {
                        errors.add(new ValidationError(
                                "INVALID_NPI",
                                "Invalid NPI format for " + entityType + ": " + value,
                                entityType + "Data.identifier"
                        ));
                    }
                }
            }
            if (!hasNpi) {
                errors.add(new ValidationError(
                        "MISSING_NPI",
                        "NPI is required for " + entityType,
                        entityType + "Data.identifier"
                ));
            }
        }
    }

    private boolean isValidNpi(String npi) {
        if (npi == null || npi.length() != 10) {
            return false;
        }
        
        // Check all digits
        if (!npi.matches("\\d{10}")) {
            return false;
        }
        
        // NPI Luhn check algorithm:
        // 1. Prefix with 80840 (constant for healthcare NPIs)
        // 2. Double every second digit from RIGHT, starting from the SECOND digit
        // 3. Sum all digits (split doubled values >9 into individual digits)
        // 4. Valid if sum mod 10 == 0
        
        String checkString = "80840" + npi;
        int sum = 0;
        
        for (int i = 0; i < checkString.length(); i++) {
            int digit = Character.getNumericValue(checkString.charAt(i));
            
            // Double every second digit from the right (odd positions from left, 0-indexed)
            // Position 0 from right = position 14 from left (length-1) -> don't double
            // Position 1 from right = position 13 from left -> double
            // etc.
            int positionFromRight = checkString.length() - 1 - i;
            if (positionFromRight % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        
        return sum % 10 == 0;
    }

    @Override
    public String getName() {
        return "NPI_VALIDATION";
    }

    @Override
    public int getPriority() {
        return 100;
    }
}
