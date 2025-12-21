package com.anthem.pagw.enricher.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of enrichment process.
 */
public class EnrichmentResult {
    
    private boolean success;
    private JsonNode enrichedData;
    private List<String> sourcesUsed = new ArrayList<>();
    private String errorMessage;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public JsonNode getEnrichedData() {
        return enrichedData;
    }

    public void setEnrichedData(JsonNode enrichedData) {
        this.enrichedData = enrichedData;
    }

    public List<String> getSourcesUsed() {
        return sourcesUsed;
    }

    public void setSourcesUsed(List<String> sourcesUsed) {
        this.sourcesUsed = sourcesUsed;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
