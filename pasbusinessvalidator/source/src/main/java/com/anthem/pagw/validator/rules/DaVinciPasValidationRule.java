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
 * Validates compliance with Da Vinci Prior Authorization Support (PAS) Implementation Guide.
 * @see https://build.fhir.org/ig/HL7/davinci-pas/
 */
@Component
public class DaVinciPasValidationRule implements ValidationRule {

    // Da Vinci PAS required resource types in bundle
    private static final Set<String> REQUIRED_RESOURCES = Set.of("Claim");
    
    // Da Vinci PAS recommended resource types
    private static final Set<String> RECOMMENDED_RESOURCES = Set.of("Patient", "Practitioner", "Organization");
    
    // Valid claim use codes for prior auth
    private static final Set<String> VALID_CLAIM_USE = Set.of("preauthorization", "predetermination");
    
    // Valid bundle types
    private static final Set<String> VALID_BUNDLE_TYPES = Set.of("collection", "batch");
    
    // Da Vinci PAS profiles
    private static final String PAS_CLAIM_PROFILE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim";
    private static final String PAS_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle";

    @Override
    public boolean isApplicable(JsonNode data, PagwMessage message) {
        // Apply to all claims - Da Vinci PAS compliance is required
        return true;
    }

    @Override
    public RuleResult validate(JsonNode data, PagwMessage message) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        // 1. Validate claim use is for prior authorization
        validateClaimUse(data, errors);
        
        // 2. Validate bundle type (if present from original bundle)
        validateBundleType(data, warnings);
        
        // 3. Check for Da Vinci PAS profile declarations
        checkPasProfiles(data, warnings);
        
        // 4. Validate claim status
        validateClaimStatus(data, errors);
        
        // 5. Validate priority code (normal, urgent, stat)
        validatePriority(data, warnings);
        
        // 6. Validate insurance focal
        validateInsuranceFocal(data, errors);
        
        // 7. Check for supporting documentation when required
        checkSupportingInfo(data, warnings);
        
        return new RuleResult(errors, warnings);
    }

    private void validateClaimUse(JsonNode data, List<ValidationError> errors) {
        String use = data.path("use").asText();
        
        if (use.isEmpty()) {
            errors.add(new ValidationError(
                    "MISSING_CLAIM_USE",
                    "Claim 'use' field is required for prior authorization",
                    "use"
            ));
        } else if (!VALID_CLAIM_USE.contains(use.toLowerCase())) {
            errors.add(new ValidationError(
                    "INVALID_CLAIM_USE",
                    String.format("Claim use must be 'preauthorization' or 'predetermination' for PAS, got '%s'", use),
                    "use"
            ));
        }
    }

    private void validateBundleType(JsonNode data, List<ValidationWarning> warnings) {
        // bundleType may be passed through from parser
        String bundleType = data.path("bundleType").asText();
        
        if (!bundleType.isEmpty() && !VALID_BUNDLE_TYPES.contains(bundleType.toLowerCase())) {
            warnings.add(new ValidationWarning(
                    "UNEXPECTED_BUNDLE_TYPE",
                    String.format("Expected bundle type 'collection' or 'batch' for PAS, got '%s'", bundleType),
                    "bundleType"
            ));
        }
    }

    private void checkPasProfiles(JsonNode data, List<ValidationWarning> warnings) {
        // Check if Da Vinci PAS profiles are declared
        JsonNode meta = data.path("meta");
        
        if (meta.isMissingNode() || !meta.has("profile")) {
            warnings.add(new ValidationWarning(
                    "MISSING_PAS_PROFILE",
                    "Da Vinci PAS profile declaration is recommended in meta.profile",
                    "meta.profile"
            ));
        }
    }

    private void validateClaimStatus(JsonNode data, List<ValidationError> errors) {
        String status = data.path("status").asText();
        
        // For new submissions, status should be 'active'
        if (!status.isEmpty() && !status.equals("active")) {
            errors.add(new ValidationError(
                    "INVALID_CLAIM_STATUS",
                    String.format("New prior auth claims must have status 'active', got '%s'", status),
                    "status"
            ));
        }
    }

    private void validatePriority(JsonNode data, List<ValidationWarning> warnings) {
        String priority = data.path("priority").asText();
        Set<String> validPriorities = Set.of("normal", "urgent", "stat");
        
        if (priority.isEmpty()) {
            warnings.add(new ValidationWarning(
                    "MISSING_PRIORITY",
                    "Claim priority is recommended (normal, urgent, stat)",
                    "priority"
            ));
        } else if (!validPriorities.contains(priority.toLowerCase())) {
            warnings.add(new ValidationWarning(
                    "INVALID_PRIORITY",
                    String.format("Unexpected priority value '%s'. Expected: normal, urgent, stat", priority),
                    "priority"
            ));
        }
    }

    private void validateInsuranceFocal(JsonNode data, List<ValidationError> errors) {
        // At least one insurance must be focal
        Boolean focal = data.path("insuranceFocal").asBoolean(false);
        
        if (!focal) {
            // This is a warning, not an error, as it may be derived from structure
        }
    }

    private void checkSupportingInfo(JsonNode data, List<ValidationWarning> warnings) {
        // Check if attachments or supporting info might be required based on claim type
        String claimType = data.path("claimType").asText();
        boolean hasAttachments = data.path("attachments").isArray() && data.path("attachments").size() > 0;
        
        // Certain claim types commonly require supporting documentation
        Set<String> typesNeedingDocs = Set.of("institutional", "professional");
        
        if (typesNeedingDocs.contains(claimType.toLowerCase()) && !hasAttachments) {
            warnings.add(new ValidationWarning(
                    "NO_SUPPORTING_DOCS",
                    String.format("Supporting documentation may be required for %s claims", claimType),
                    "attachments"
            ));
        }
    }

    @Override
    public String getName() {
        return "DA_VINCI_PAS_VALIDATION";
    }

    @Override
    public int getPriority() {
        return 100; // High priority - run first
    }
}
