package com.anthem.pagw.callback.model;

import java.time.Instant;
import java.util.Map;

/**
 * Incoming callback request from downstream system.
 */
public class CallbackRequest {
    
    private String referenceId;
    private String pagwId;
    private String status;
    private String outcome;
    private String claimNumber;
    private String adjudicationDate;
    private String errorCode;
    private String errorMessage;
    private Map<String, Object> additionalData;
    private Instant receivedAt;

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public String getPagwId() {
        return pagwId;
    }

    public void setPagwId(String pagwId) {
        this.pagwId = pagwId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public void setClaimNumber(String claimNumber) {
        this.claimNumber = claimNumber;
    }

    public String getAdjudicationDate() {
        return adjudicationDate;
    }

    public void setAdjudicationDate(String adjudicationDate) {
        this.adjudicationDate = adjudicationDate;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getAdditionalData() {
        return additionalData;
    }

    public void setAdditionalData(Map<String, Object> additionalData) {
        this.additionalData = additionalData;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
