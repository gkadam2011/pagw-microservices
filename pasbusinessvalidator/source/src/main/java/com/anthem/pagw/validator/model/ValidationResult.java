package com.anthem.pagw.validator.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of business validation.
 */
public class ValidationResult {
    
    private String pagwId;
    private boolean valid;
    private List<ValidationError> errors = new ArrayList<>();
    private List<ValidationWarning> warnings = new ArrayList<>();
    private String summary;
    private JsonNode validatedData;

    public String getPagwId() {
        return pagwId;
    }

    public void setPagwId(String pagwId) {
        this.pagwId = pagwId;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    public void setErrors(List<ValidationError> errors) {
        this.errors = errors;
    }

    public List<ValidationWarning> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<ValidationWarning> warnings) {
        this.warnings = warnings;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public JsonNode getValidatedData() {
        return validatedData;
    }

    public void setValidatedData(JsonNode validatedData) {
        this.validatedData = validatedData;
    }
}
