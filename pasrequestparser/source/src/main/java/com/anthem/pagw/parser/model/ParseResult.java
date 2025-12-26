package com.anthem.pagw.parser.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of parsing a FHIR bundle.
 */
public class ParseResult {
    
    private boolean valid;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private ParsedClaim parsedData;
    private boolean hasAttachments;
    private int attachmentCount;

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    public ParsedClaim getParsedData() {
        return parsedData;
    }

    public void setParsedData(ParsedClaim parsedData) {
        this.parsedData = parsedData;
    }

    public boolean hasAttachments() {
        return hasAttachments;
    }

    public void setHasAttachments(boolean hasAttachments) {
        this.hasAttachments = hasAttachments;
    }

    public int getAttachmentCount() {
        return attachmentCount;
    }

    public void setAttachmentCount(int attachmentCount) {
        this.attachmentCount = attachmentCount;
    }
}
