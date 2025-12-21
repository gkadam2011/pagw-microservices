package com.anthem.pagw.validator.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.validator.model.ValidationError;
import com.anthem.pagw.validator.model.ValidationResult;
import com.anthem.pagw.validator.model.ValidationWarning;
import com.anthem.pagw.validator.rules.ValidationRule;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Business Validator Service.
 * Applies business rules to validate claims.
 */
@Service
public class BusinessValidatorService {

    private static final Logger log = LoggerFactory.getLogger(BusinessValidatorService.class);
    
    private final List<ValidationRule> validationRules;

    public BusinessValidatorService(List<ValidationRule> validationRules) {
        this.validationRules = validationRules;
    }

    /**
     * Validate claim data against business rules.
     * 
     * @param claimData The claim data JSON
     * @param message The PAGW message context
     * @return ValidationResult with errors and warnings
     */
    public ValidationResult validate(String claimData, PagwMessage message) {
        ValidationResult result = new ValidationResult();
        result.setPagwId(message.getPagwId());
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        try {
            JsonNode data = JsonUtils.parseJson(claimData);
            
            // Run all validation rules
            for (ValidationRule rule : validationRules) {
                if (rule.isApplicable(data, message)) {
                    var ruleResult = rule.validate(data, message);
                    errors.addAll(ruleResult.getErrors());
                    warnings.addAll(ruleResult.getWarnings());
                }
            }
            
            // Additional built-in validations
            validateRequiredFields(data, errors);
            validateClaimType(data, errors);
            validateDates(data, errors, warnings);
            validateAmounts(data, errors, warnings);
            validateReferences(data, errors);
            
            result.setErrors(errors);
            result.setWarnings(warnings);
            result.setValid(errors.isEmpty());
            result.setValidatedData(data);
            
            if (!errors.isEmpty()) {
                result.setSummary(String.format("%d validation errors: %s", 
                        errors.size(), 
                        errors.stream().map(ValidationError::getMessage).limit(3).toList()));
            }
            
        } catch (Exception e) {
            log.error("Validation error: pagwId={}", message.getPagwId(), e);
            errors.add(new ValidationError("PARSE_ERROR", "Failed to parse claim data: " + e.getMessage(), "root"));
            result.setErrors(errors);
            result.setValid(false);
            result.setSummary("Failed to parse claim data");
        }
        
        return result;
    }

    private void validateRequiredFields(JsonNode data, List<ValidationError> errors) {
        // Check required claim fields
        String[] requiredFields = {"pagwId", "claimId", "claimType", "patientReference", "providerReference"};
        
        for (String field : requiredFields) {
            if (!data.has(field) || data.path(field).asText().isEmpty()) {
                errors.add(new ValidationError(
                        "REQUIRED_FIELD_MISSING",
                        "Required field is missing: " + field,
                        field
                ));
            }
        }
        
        // Check patient data
        if (!data.has("patientData") || data.path("patientData").isMissingNode()) {
            errors.add(new ValidationError(
                    "PATIENT_DATA_MISSING",
                    "Patient data is required",
                    "patientData"
            ));
        }
    }

    private void validateClaimType(JsonNode data, List<ValidationError> errors) {
        String claimType = data.path("claimType").asText();
        List<String> validTypes = List.of("professional", "institutional", "oral", "vision", "pharmacy");
        
        if (!claimType.isEmpty() && !validTypes.contains(claimType.toLowerCase())) {
            errors.add(new ValidationError(
                    "INVALID_CLAIM_TYPE",
                    "Invalid claim type: " + claimType + ". Valid types: " + validTypes,
                    "claimType"
            ));
        }
    }

    private void validateDates(JsonNode data, List<ValidationError> errors, List<ValidationWarning> warnings) {
        String created = data.path("created").asText();
        
        if (!created.isEmpty()) {
            try {
                java.time.LocalDate createdDate = java.time.LocalDate.parse(created.substring(0, 10));
                java.time.LocalDate now = java.time.LocalDate.now();
                
                // Error if date is in the future
                if (createdDate.isAfter(now)) {
                    errors.add(new ValidationError(
                            "FUTURE_DATE",
                            "Claim created date cannot be in the future",
                            "created"
                    ));
                }
                
                // Warning if date is more than 1 year old
                if (createdDate.isBefore(now.minusYears(1))) {
                    warnings.add(new ValidationWarning(
                            "OLD_CLAIM",
                            "Claim is more than 1 year old",
                            "created"
                    ));
                }
            } catch (Exception e) {
                errors.add(new ValidationError(
                        "INVALID_DATE_FORMAT",
                        "Invalid date format for created field",
                        "created"
                ));
            }
        }
    }

    private void validateAmounts(JsonNode data, List<ValidationError> errors, List<ValidationWarning> warnings) {
        // Validate total amount
        if (data.has("totalValue")) {
            double total = data.path("totalValue").asDouble();
            
            if (total < 0) {
                errors.add(new ValidationError(
                        "NEGATIVE_AMOUNT",
                        "Total amount cannot be negative",
                        "totalValue"
                ));
            }
            
            if (total > 1000000) {
                warnings.add(new ValidationWarning(
                        "HIGH_VALUE_CLAIM",
                        "Claim total exceeds $1,000,000 - requires manual review",
                        "totalValue"
                ));
            }
        }
        
        // Validate line items
        JsonNode lineItems = data.path("lineItems");
        if (lineItems.isArray()) {
            double lineTotal = 0;
            for (JsonNode item : lineItems) {
                double net = item.path("net").asDouble();
                if (net < 0) {
                    errors.add(new ValidationError(
                            "NEGATIVE_LINE_AMOUNT",
                            "Line item amount cannot be negative",
                            "lineItems[" + item.path("sequence").asInt() + "].net"
                    ));
                }
                lineTotal += net;
            }
            
            // Validate line total matches claim total
            double claimTotal = data.path("totalValue").asDouble();
            if (claimTotal > 0 && Math.abs(lineTotal - claimTotal) > 0.01) {
                warnings.add(new ValidationWarning(
                        "TOTAL_MISMATCH",
                        String.format("Line item total (%.2f) does not match claim total (%.2f)", lineTotal, claimTotal),
                        "totalValue"
                ));
            }
        }
    }

    private void validateReferences(JsonNode data, List<ValidationError> errors) {
        // Validate patient reference format
        String patientRef = data.path("patientReference").asText();
        if (!patientRef.isEmpty() && !patientRef.startsWith("Patient/")) {
            errors.add(new ValidationError(
                    "INVALID_REFERENCE_FORMAT",
                    "Patient reference must start with 'Patient/'",
                    "patientReference"
            ));
        }
        
        // Validate provider reference format
        String providerRef = data.path("providerReference").asText();
        if (!providerRef.isEmpty() && 
            !providerRef.startsWith("Practitioner/") && 
            !providerRef.startsWith("Organization/")) {
            errors.add(new ValidationError(
                    "INVALID_REFERENCE_FORMAT",
                    "Provider reference must start with 'Practitioner/' or 'Organization/'",
                    "providerReference"
            ));
        }
    }
}
