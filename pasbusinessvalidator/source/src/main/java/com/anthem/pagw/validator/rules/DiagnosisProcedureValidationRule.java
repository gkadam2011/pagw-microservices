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
 * Validates diagnosis codes (ICD-10-CM) and procedure codes (CPT/HCPCS).
 */
@Component
public class DiagnosisProcedureValidationRule implements ValidationRule {

    // ICD-10-CM pattern: Letter followed by 2 digits, optional decimal and up to 4 more chars
    // Examples: E11.9, I10, Z23, S72.001A
    private static final Pattern ICD10_PATTERN = Pattern.compile("^[A-TV-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?$");
    
    // CPT pattern: 5 digits, optionally followed by F or T modifier
    // Examples: 99213, 99214, 27447
    private static final Pattern CPT_PATTERN = Pattern.compile("^[0-9]{5}[FT]?$");
    
    // HCPCS Level II pattern: Letter followed by 4 digits
    // Examples: A0425, J1234, G0008
    private static final Pattern HCPCS_PATTERN = Pattern.compile("^[A-V][0-9]{4}$");
    
    // Known code systems
    private static final String ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String ICD10_SYSTEM_ALT = "http://hl7.org/fhir/sid/icd-10";
    private static final String CPT_SYSTEM = "http://www.ama-assn.org/go/cpt";
    private static final String HCPCS_SYSTEM = "urn:oid:2.16.840.1.113883.6.285";
    
    // Common invalid/placeholder codes
    private static final Set<String> INVALID_PLACEHOLDER_CODES = Set.of(
            "00000", "99999", "XXXXX", "TEST", "SAMPLE", "NONE", "N/A"
    );

    @Override
    public boolean isApplicable(JsonNode data, PagwMessage message) {
        // Apply if there are diagnosis codes or line items (procedure codes)
        return data.has("diagnosisCodes") || data.has("lineItems");
    }

    @Override
    public RuleResult validate(JsonNode data, PagwMessage message) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        // Validate diagnosis codes
        validateDiagnosisCodes(data.path("diagnosisCodes"), errors, warnings);
        
        // Validate procedure codes from line items
        validateProcedureCodes(data.path("lineItems"), errors, warnings);
        
        // Check for at least one diagnosis code (usually required for prior auth)
        if (!data.has("diagnosisCodes") || data.path("diagnosisCodes").size() == 0) {
            warnings.add(new ValidationWarning(
                    "NO_DIAGNOSIS_CODES",
                    "At least one diagnosis code is typically required for prior authorization",
                    "diagnosisCodes"
            ));
        }
        
        // Check for at least one procedure code
        if (!data.has("lineItems") || data.path("lineItems").size() == 0) {
            warnings.add(new ValidationWarning(
                    "NO_PROCEDURE_CODES",
                    "At least one procedure code is typically required for prior authorization",
                    "lineItems"
            ));
        }
        
        return new RuleResult(errors, warnings);
    }

    private void validateDiagnosisCodes(JsonNode diagnosisCodes, List<ValidationError> errors, List<ValidationWarning> warnings) {
        if (!diagnosisCodes.isArray()) {
            return;
        }
        
        boolean hasPrincipal = false;
        int sequence = 0;
        
        for (JsonNode dx : diagnosisCodes) {
            sequence++;
            String code = dx.path("code").asText();
            String system = dx.path("system").asText();
            String type = dx.path("type").asText();
            
            // Check for placeholder codes
            if (INVALID_PLACEHOLDER_CODES.contains(code.toUpperCase())) {
                errors.add(new ValidationError(
                        "INVALID_DIAGNOSIS_CODE",
                        String.format("Invalid/placeholder diagnosis code: %s", code),
                        "diagnosisCodes[" + sequence + "].code"
                ));
                continue;
            }
            
            // Validate ICD-10 format if system indicates ICD-10
            if (isIcd10System(system)) {
                if (!ICD10_PATTERN.matcher(code.toUpperCase()).matches()) {
                    errors.add(new ValidationError(
                            "INVALID_ICD10_FORMAT",
                            String.format("Invalid ICD-10 code format: %s (expected pattern like E11.9, I10)", code),
                            "diagnosisCodes[" + sequence + "].code"
                    ));
                }
            } else if (!system.isEmpty()) {
                warnings.add(new ValidationWarning(
                        "UNKNOWN_DIAGNOSIS_SYSTEM",
                        String.format("Unknown diagnosis code system: %s. Expected ICD-10-CM", system),
                        "diagnosisCodes[" + sequence + "].system"
                ));
            }
            
            // Track principal diagnosis
            if ("principal".equalsIgnoreCase(type)) {
                hasPrincipal = true;
            }
        }
        
        // Check for principal diagnosis
        if (diagnosisCodes.size() > 0 && !hasPrincipal) {
            warnings.add(new ValidationWarning(
                    "NO_PRINCIPAL_DIAGNOSIS",
                    "No principal diagnosis type indicated. First diagnosis will be treated as primary.",
                    "diagnosisCodes"
            ));
        }
    }

    private void validateProcedureCodes(JsonNode lineItems, List<ValidationError> errors, List<ValidationWarning> warnings) {
        if (!lineItems.isArray()) {
            return;
        }
        
        int sequence = 0;
        
        for (JsonNode item : lineItems) {
            sequence++;
            String code = item.path("productOrService").asText();
            String system = item.path("system").asText();
            
            if (code.isEmpty()) {
                errors.add(new ValidationError(
                        "MISSING_PROCEDURE_CODE",
                        "Line item missing procedure code",
                        "lineItems[" + sequence + "].productOrService"
                ));
                continue;
            }
            
            // Check for placeholder codes
            if (INVALID_PLACEHOLDER_CODES.contains(code.toUpperCase())) {
                errors.add(new ValidationError(
                        "INVALID_PROCEDURE_CODE",
                        String.format("Invalid/placeholder procedure code: %s", code),
                        "lineItems[" + sequence + "].productOrService"
                ));
                continue;
            }
            
            // Validate CPT or HCPCS format
            if (isCptSystem(system) || system.isEmpty()) {
                // Try CPT first (5 digits)
                if (!CPT_PATTERN.matcher(code).matches() && !HCPCS_PATTERN.matcher(code).matches()) {
                    warnings.add(new ValidationWarning(
                            "UNRECOGNIZED_PROCEDURE_FORMAT",
                            String.format("Procedure code '%s' doesn't match CPT (5 digits) or HCPCS (letter + 4 digits) format", code),
                            "lineItems[" + sequence + "].productOrService"
                    ));
                }
            }
            
            // Validate quantity
            int quantity = item.path("quantity").asInt(0);
            if (quantity <= 0) {
                warnings.add(new ValidationWarning(
                        "INVALID_QUANTITY",
                        "Line item quantity should be > 0",
                        "lineItems[" + sequence + "].quantity"
                ));
            }
            
            // Validate that unit price or net amount is present
            double unitPrice = item.path("unitPrice").asDouble(0);
            double net = item.path("net").asDouble(0);
            if (unitPrice <= 0 && net <= 0) {
                warnings.add(new ValidationWarning(
                        "MISSING_AMOUNT",
                        "Line item should have unitPrice or net amount",
                        "lineItems[" + sequence + "]"
                ));
            }
        }
    }

    private boolean isIcd10System(String system) {
        return ICD10_SYSTEM.equals(system) || ICD10_SYSTEM_ALT.equals(system) || 
               system.contains("icd-10");
    }

    private boolean isCptSystem(String system) {
        return CPT_SYSTEM.equals(system) || HCPCS_SYSTEM.equals(system) ||
               system.contains("cpt") || system.contains("hcpcs");
    }

    @Override
    public String getName() {
        return "DIAGNOSIS_PROCEDURE_VALIDATION";
    }

    @Override
    public int getPriority() {
        return 85; // Run after Da Vinci PAS, before NPI
    }
}
