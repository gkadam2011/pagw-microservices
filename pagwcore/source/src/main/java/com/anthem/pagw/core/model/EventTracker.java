package com.anthem.pagw.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Event tracker model - represents a single event in the PAGW pipeline.
 * Maps to the event_tracker database table.
 * 
 * <p>Events provide fine-grained observability into request processing stages.
 * Each event has a monotonically increasing sequence number within a request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventTracker {
    
    private Long id;
    private String tenant;
    private String pagwId;
    
    // Event classification
    private String stage;              // Pipeline stage (PARSING, VALIDATION, etc.)
    private String eventType;          // Specific event (PARSE_START, VAL_OK, etc.)
    private String status;             // STARTED, SUCCESS, FAILURE, RETRY
    private String executionStatus;    // SUCCESS, FAILURE, ERROR, TIMEOUT (from V005 enum)
    
    // Ordering and retry context
    private Long sequenceNo;           // Monotonic sequence number for ordering
    private Integer attempt;           // Retry attempt (0 = first try)
    private Boolean retryable;         // Can this event be retried?
    private Instant nextRetryAt;       // When to retry (if retryable)
    
    // External reference (downstream system tracking)
    private String externalReference;
    
    // Performance tracking
    private Integer durationMs;        // Event duration in milliseconds
    
    // Error tracking
    private String errorCode;
    private String errorMessage;
    
    // Execution context
    private String workerId;           // Pod name, Lambda request ID, worker identifier
    private String metadata;           // JSON metadata (no PHI - use S3 for PHI)
    
    // Timestamps
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;
    
    // Event type constants
    
    // Orchestrator events
    public static final String EVENT_REQUEST_RECEIVED = "REQUEST_RECEIVED";
    public static final String EVENT_WORKFLOW_START = "WORKFLOW_START";
    public static final String EVENT_WORKFLOW_COMPLETE = "WORKFLOW_COMPLETE";
    
    // Parser events
    public static final String EVENT_PARSE_START = "PARSE_START";
    public static final String EVENT_PARSE_OK = "PARSE_OK";
    public static final String EVENT_PARSE_FAIL = "PARSE_FAIL";
    
    // Validator events
    public static final String EVENT_VAL_START = "VAL_START";
    public static final String EVENT_VAL_OK = "VAL_OK";
    public static final String EVENT_VAL_FAIL = "VAL_FAIL";
    
    // Enricher events
    public static final String EVENT_ENRICH_START = "ENRICH_START";
    public static final String EVENT_ENRICH_OK = "ENRICH_OK";
    public static final String EVENT_ENRICH_FAIL = "ENRICH_FAIL";
    
    // Attachment events
    public static final String EVENT_ATTACH_START = "ATTACH_START";
    public static final String EVENT_ATTACH_OK = "ATTACH_OK";
    public static final String EVENT_ATTACH_FAIL = "ATTACH_FAIL";
    public static final String EVENT_CDEX_PULL_START = "CDEX_PULL_START";
    public static final String EVENT_CDEX_PULL_OK = "CDEX_PULL_OK";
    public static final String EVENT_CDEX_PULL_FAIL = "CDEX_PULL_FAIL";
    
    // Converter events
    public static final String EVENT_CONVERT_START = "CONVERT_START";
    public static final String EVENT_CONVERT_OK = "CONVERT_OK";
    public static final String EVENT_CONVERT_FAIL = "CONVERT_FAIL";
    
    // API Connector events (downstream external API calls)
    public static final String EVENT_API_CON_START = "API_CON_START";
    public static final String EVENT_API_CON_OK = "API_CON_OK";
    public static final String EVENT_API_CON_FAIL = "API_CON_FAIL";
    public static final String EVENT_API_CON_TIMEOUT = "API_CON_TIMEOUT";
    public static final String EVENT_API_CON_ERROR = "API_CON_ERROR";
    
    // Response Builder events
    public static final String EVENT_RESPONSE_START = "RESPONSE_START";
    public static final String EVENT_RESPONSE_OK = "RESPONSE_OK";
    public static final String EVENT_RESPONSE_FAIL = "RESPONSE_FAIL";
    
    // Callback Handler events
    public static final String EVENT_CALLBACK_START = "CALLBACK_START";
    public static final String EVENT_CALLBACK_OK = "CALLBACK_OK";
    public static final String EVENT_CALLBACK_FAIL = "CALLBACK_FAIL";
    
    // Subscription Handler events
    public static final String EVENT_NOTIFY_START = "NOTIFY_START";
    public static final String EVENT_NOTIFY_OK = "NOTIFY_OK";
    public static final String EVENT_NOTIFY_FAIL = "NOTIFY_FAIL";
    
    // Outbox Publisher events
    public static final String EVENT_PUBLISH_START = "PUBLISH_START";
    public static final String EVENT_PUBLISH_OK = "PUBLISH_OK";
    public static final String EVENT_PUBLISH_FAIL = "PUBLISH_FAIL";
    
    // Status constants
    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILURE = "FAILURE";
    public static final String STATUS_RETRY = "RETRY";
    
    // Stage constants (aligned with event_type_enum in V005)
    public static final String STAGE_ORCHESTRATION = "ORCHESTRATION";
    public static final String STAGE_PARSING = "PARSING";
    public static final String STAGE_VALIDATION = "VALIDATION";
    public static final String STAGE_ENRICHMENT = "ENRICHMENT";
    public static final String STAGE_ATTACHMENT = "ATTACHMENT";
    public static final String STAGE_CONVERSION = "CONVERSION";
    public static final String STAGE_SUBMISSION = "SUBMISSION";
    public static final String STAGE_RESPONSE = "RESPONSE";
    public static final String STAGE_CALLBACK = "CALLBACK";
    
    /**
     * Check if this event represents a failure.
     */
    public boolean isFailure() {
        return STATUS_FAILURE.equals(status) || 
               (eventType != null && eventType.endsWith("_FAIL"));
    }
    
    /**
     * Check if this event represents a success.
     */
    public boolean isSuccess() {
        return STATUS_SUCCESS.equals(status) || 
               (eventType != null && eventType.endsWith("_OK"));
    }
    
    /**
     * Check if this event is retryable.
     */
    public boolean canRetry() {
        return Boolean.TRUE.equals(retryable) && 
               (nextRetryAt == null || nextRetryAt.isBefore(Instant.now()));
    }
}
