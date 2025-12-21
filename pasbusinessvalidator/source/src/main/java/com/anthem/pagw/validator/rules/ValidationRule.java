package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.validator.model.ValidationError;
import com.anthem.pagw.validator.model.ValidationWarning;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Interface for pluggable validation rules.
 */
public interface ValidationRule {
    
    /**
     * Check if this rule is applicable to the given data.
     */
    boolean isApplicable(JsonNode data, PagwMessage message);
    
    /**
     * Execute the validation rule.
     */
    RuleResult validate(JsonNode data, PagwMessage message);
    
    /**
     * Get the rule name.
     */
    String getName();
    
    /**
     * Get the rule priority (higher = runs first).
     */
    default int getPriority() {
        return 0;
    }
    
    /**
     * Result of a single rule validation.
     */
    class RuleResult {
        private List<ValidationError> errors;
        private List<ValidationWarning> warnings;
        
        public RuleResult(List<ValidationError> errors, List<ValidationWarning> warnings) {
            this.errors = errors;
            this.warnings = warnings;
        }
        
        public static RuleResult success() {
            return new RuleResult(List.of(), List.of());
        }
        
        public static RuleResult error(ValidationError error) {
            return new RuleResult(List.of(error), List.of());
        }
        
        public static RuleResult warning(ValidationWarning warning) {
            return new RuleResult(List.of(), List.of(warning));
        }
        
        public List<ValidationError> getErrors() {
            return errors;
        }
        
        public List<ValidationWarning> getWarnings() {
            return warnings;
        }
    }
}
