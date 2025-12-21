package com.anthem.pagw.callback.model;

import java.time.Instant;

/**
 * Result of callback processing.
 */
public class CallbackResult {
    
    private String pagwId;
    private String externalReferenceId;
    private String status;
    private String outcome;
    private String disposition;
    private boolean error;
    private String errorCode;
    private String errorMessage;
    private String claimNumber;
    private String adjudicationDate;
    private Instant processedAt;

    public String getPagwId() {
        return pagwId;
    }

    public void setPagwId(String pagwId) {
        this.pagwId = pagwId;
    }

    public String getExternalReferenceId() {
        return externalReferenceId;
    }

    public void setExternalReferenceId(String externalReferenceId) {
        this.externalReferenceId = externalReferenceId;
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

    public String getDisposition() {
        return disposition;
    }

    public void setDisposition(String disposition) {
        this.disposition = disposition;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
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

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
