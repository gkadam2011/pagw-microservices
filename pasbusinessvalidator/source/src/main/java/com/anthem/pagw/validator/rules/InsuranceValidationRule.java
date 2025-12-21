package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.validator.model.ValidationError;
import com.anthem.pagw.validator.model.ValidationWarning;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates insurance/coverage information.
 */
@Component
public class InsuranceValidationRule implements ValidationRule {

    private static final Set<String> VALID_RELATIONSHIPS = Set.of(
            "self", "spouse", "child", "other"
    );

    @Override
    public boolean isApplicable(JsonNode data, PagwMessage message) {
        return data.has("coverageReference") || data.has("insuranceSequence");
    }

    @Override
    public RuleResult validate(JsonNode data, PagwMessage message) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        // Check coverage reference
        String coverageRef = data.path("coverageReference").asText();
        if (coverageRef.isEmpty()) {
            errors.add(new ValidationError(
                    "MISSING_COVERAGE",
                    "Insurance coverage reference is required",
                    "coverageReference"
            ));
        } else if (!coverageRef.startsWith("Coverage/")) {
            errors.add(new ValidationError(
                    "INVALID_COVERAGE_REF",
                    "Coverage reference must start with 'Coverage/'",
                    "coverageReference"
            ));
        }
        
        // Check insurance sequence
        int sequence = data.path("insuranceSequence").asInt(-1);
        if (sequence < 1) {
            warnings.add(new ValidationWarning(
                    "INVALID_INSURANCE_SEQUENCE",
                    "Insurance sequence should be >= 1",
                    "insuranceSequence"
            ));
        }
        
        // Check focal flag
        if (!data.has("insuranceFocal")) {
            warnings.add(new ValidationWarning(
                    "MISSING_FOCAL_FLAG",
                    "Insurance focal flag is recommended",
                    "insuranceFocal"
            ));
        }
        
        return new RuleResult(errors, warnings);
    }

    @Override
    public String getName() {
        return "INSURANCE_VALIDATION";
    }

    @Override
    public int getPriority() {
        return 90;
    }
}
