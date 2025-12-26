package com.anthem.pagw.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Map;

/**
 * Core request tracker model - represents a PA request throughout its lifecycle.
 * Maps to the request_tracker database table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestTracker {

    private String pagwId;
    private String status;
    
    // Request metadata
    private String tenant;
    private String sourceSystem;
    private String requestType;
    
    // Workflow tracking
    private String lastStage;
    private String nextStage;
    private String workflowId;
    
    // Error tracking
    private String lastErrorCode;
    private String lastErrorMsg;
    private int retryCount;
    
    // S3 pointers (encrypted paths)
    private String rawS3Bucket;
    private String rawS3Key;
    private String enrichedS3Bucket;
    private String enrichedS3Key;
    private String finalS3Bucket;
    private String finalS3Key;
    
    // PHI indicator
    private boolean containsPhi;
    
    // Idempotency
    private String idempotencyKey;
    
    // Legacy fields for backward compatibility
    private String externalRequestId;
    private String clientId;
    private String memberId;
    private String providerId;
    private Map<String, Object> metadata;
    
    // Timestamps
    private Instant receivedAt;
    private Instant completedAt;
    private Instant callbackSentAt;
    private Instant createdAt;
    private Instant updatedAt;

    // Request type constants
    public static final String TYPE_SUBMIT = "SUBMIT";
    public static final String TYPE_INQUIRY = "INQUIRY";
    public static final String TYPE_CANCEL = "CANCEL";
    public static final String TYPE_UPDATE = "UPDATE";
    
    // Status constants
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_ENRICHING = "ENRICHING";
    public static final String STATUS_PROCESSING_ATTACHMENTS = "PROCESSING_ATTACHMENTS";
    public static final String STATUS_MAPPING = "MAPPING";
    public static final String STATUS_CALLING_DOWNSTREAM = "CALLING_DOWNSTREAM";
    public static final String STATUS_BUILDING_RESPONSE = "BUILDING_RESPONSE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    
    // Stage constants
    public static final String STAGE_REQUEST_PARSER = "REQUEST_PARSER";
    public static final String STAGE_ATTACHMENT_HANDLER = "ATTACHMENT_HANDLER";
    public static final String STAGE_BUSINESS_VALIDATOR = "BUSINESS_VALIDATOR";
    public static final String STAGE_REQUEST_ENRICHER = "REQUEST_ENRICHER";
    public static final String STAGE_CANONICAL_MAPPER = "CANONICAL_MAPPER";
    public static final String STAGE_API_ORCHESTRATOR = "API_ORCHESTRATOR";
    public static final String STAGE_RESPONSE_BUILDER = "RESPONSE_BUILDER";
    public static final String STAGE_CALLBACK_HANDLER = "CALLBACK_HANDLER";
}
