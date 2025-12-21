package com.anthem.pagw.callback.service;

import com.anthem.pagw.callback.model.CallbackResult;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Service for processing callbacks from downstream systems.
 */
@Service
public class CallbackHandlerService {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandlerService.class);

    private final JdbcTemplate jdbcTemplate;

    public CallbackHandlerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Process a callback from a downstream system.
     * 
     * @param responseData The original API response data
     * @param message The PAGW message context
     * @return CallbackResult with processed information
     */
    public CallbackResult processCallback(String responseData, PagwMessage message) {
        CallbackResult result = new CallbackResult();
        result.setPagwId(message.getPagwId());
        result.setExternalReferenceId(message.getExternalReferenceId());
        result.setProcessedAt(Instant.now());
        
        try {
            // Parse original response
            JsonNode originalResponse = JsonUtils.parseJson(responseData);
            
            // Determine callback status based on API response
            String apiStatus = message.getApiResponseStatus();
            
            switch (apiStatus != null ? apiStatus.toUpperCase() : "UNKNOWN") {
                case "ACCEPTED":
                case "COMPLETE":
                case "PROCESSED":
                    result.setStatus("COMPLETED");
                    result.setError(false);
                    result.setOutcome("complete");
                    result.setDisposition("Claim processed successfully");
                    break;
                    
                case "PENDING":
                case "IN_PROGRESS":
                    result.setStatus("PENDING");
                    result.setError(false);
                    result.setOutcome("queued");
                    result.setDisposition("Claim still being processed");
                    break;
                    
                case "REJECTED":
                case "DENIED":
                    result.setStatus("REJECTED");
                    result.setError(true);
                    result.setOutcome("error");
                    result.setDisposition("Claim was rejected");
                    result.setErrorCode("CLAIM_REJECTED");
                    result.setErrorMessage(extractErrorMessage(originalResponse));
                    break;
                    
                case "ERROR":
                case "FAILED":
                    result.setStatus("ERROR");
                    result.setError(true);
                    result.setOutcome("error");
                    result.setDisposition("Processing error occurred");
                    result.setErrorCode("PROCESSING_ERROR");
                    result.setErrorMessage(extractErrorMessage(originalResponse));
                    break;
                    
                default:
                    result.setStatus("UNKNOWN");
                    result.setError(false);
                    result.setOutcome("queued");
                    result.setDisposition("Status unknown - awaiting further update");
            }
            
            // Extract additional info from response
            if (originalResponse.has("rawResponse")) {
                try {
                    JsonNode rawResponse = JsonUtils.parseJson(originalResponse.path("rawResponse").asText());
                    result.setClaimNumber(rawResponse.path("claimNumber").asText(null));
                    result.setAdjudicationDate(rawResponse.path("adjudicationDate").asText(null));
                } catch (Exception e) {
                    // Ignore parse errors for raw response
                }
            }
            
            // Track the callback in database
            trackCallback(result);
            
            log.info("Processed callback: pagwId={}, status={}, outcome={}", 
                    message.getPagwId(), result.getStatus(), result.getOutcome());
            
        } catch (Exception e) {
            log.error("Callback processing error: pagwId={}", message.getPagwId(), e);
            result.setStatus("ERROR");
            result.setError(true);
            result.setErrorCode("CALLBACK_PROCESSING_ERROR");
            result.setErrorMessage(e.getMessage());
            result.setOutcome("error");
        }
        
        return result;
    }

    private String extractErrorMessage(JsonNode response) {
        // Try various common error message fields
        if (response.has("errorMessage")) {
            return response.path("errorMessage").asText();
        }
        if (response.has("error")) {
            JsonNode error = response.path("error");
            if (error.isTextual()) {
                return error.asText();
            }
            return error.path("message").asText("Unknown error");
        }
        if (response.has("message")) {
            return response.path("message").asText();
        }
        return "Processing failed without specific error message";
    }

    private void trackCallback(CallbackResult result) {
        String sql = """
            INSERT INTO pagw.event_tracker 
            (pagw_id, stage, event_type, status, external_reference, completed_at)
            VALUES (?, 'callback-handler', 'CALLBACK', ?, ?, ?)
            """;
        
        jdbcTemplate.update(sql,
                result.getPagwId(),
                result.getStatus(),
                result.getExternalReferenceId(),
                java.sql.Timestamp.from(result.getProcessedAt())
        );
    }
}
