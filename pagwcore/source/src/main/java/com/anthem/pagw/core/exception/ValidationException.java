package com.anthem.pagw.core.exception;

import java.util.ArrayList;
import java.util.List;

/**
 * Exception for validation errors (FHIR structure, business rules, etc.).
 * Returns HTTP 400 Bad Request.
 * Maps to FHIR OperationOutcome with issue type.
 */
public class ValidationException extends PagwException {

    private final String field;
    private final String issueType;
    private final List<ValidationError> errors;

    public ValidationException(String message) {
        this(ErrorCode.VALIDATION_ERROR, message, null, null, "value");
    }

    public ValidationException(String message, String pagwId) {
        this(ErrorCode.VALIDATION_ERROR, message, pagwId, null, "value");
    }

    public ValidationException(String errorCode, String message, String pagwId) {
        this(errorCode, message, pagwId, null, "value");
    }
    
    public ValidationException(String errorCode, String message, String pagwId, String field, String issueType) {
        super(errorCode, message, pagwId, null, null, ErrorSeverity.ERROR, 400, null);
        this.field = field;
        this.issueType = issueType;
        this.errors = new ArrayList<>();
    }
    
    public String getField() {
        return field;
    }
    
    public String getIssueType() {
        return issueType;
    }
    
    public List<ValidationError> getErrors() {
        return errors;
    }
    
    public ValidationException addError(String code, String message, String field) {
        this.errors.add(new ValidationError(code, message, field, "error"));
        return this;
    }
    
    // Factory methods for common validation errors
    
    public static ValidationException missingField(String fieldName) {
        return new ValidationException(
                ErrorCode.MISSING_REQUIRED_FIELD,
                "Required field is missing: " + fieldName,
                null,
                fieldName,
                "required"
        );
    }
    
    public static ValidationException invalidValue(String fieldName, String reason) {
        return new ValidationException(
                ErrorCode.VALIDATION_ERROR,
                "Invalid value for field " + fieldName + ": " + reason,
                null,
                fieldName,
                "value"
        );
    }
    
    public static ValidationException invalidFhir(String message) {
        return new ValidationException(
                ErrorCode.INVALID_FHIR,
                message,
                null,
                null,
                "structure"
        );
    }
    
    public static ValidationException businessRule(String rule, String message) {
        return new ValidationException(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                message,
                null,
                rule,
                "business-rule"
        );
    }
    
    /**
     * Validation error detail.
     */
    public record ValidationError(String code, String message, String field, String severity) {}
}
