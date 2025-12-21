package com.anthem.pagw.converter.model;

/**
 * Result of request conversion.
 */
public class ConversionResult {
    
    private boolean success;
    private ConvertedPayload convertedPayload;
    private String targetSystem;
    private String errorMessage;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public ConvertedPayload getConvertedPayload() {
        return convertedPayload;
    }

    public void setConvertedPayload(ConvertedPayload convertedPayload) {
        this.convertedPayload = convertedPayload;
    }

    public String getTargetSystem() {
        return targetSystem;
    }

    public void setTargetSystem(String targetSystem) {
        this.targetSystem = targetSystem;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
