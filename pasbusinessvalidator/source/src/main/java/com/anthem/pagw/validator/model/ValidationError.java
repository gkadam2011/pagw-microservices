package com.anthem.pagw.validator.model;

/**
 * Validation error with code, message, and field path.
 */
public class ValidationError {
    
    private String code;
    private String message;
    private String field;
    private String severity = "ERROR";

    public ValidationError() {}

    public ValidationError(String code, String message, String field) {
        this.code = code;
        this.message = message;
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }
}
