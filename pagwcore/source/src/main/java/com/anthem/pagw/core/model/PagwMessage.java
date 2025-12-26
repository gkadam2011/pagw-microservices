package com.anthem.pagw.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SQS message wrapper for inter-service communication.
 * Standard envelope for all PAGW pipeline messages.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PagwMessage {

    // Core identifiers
    private String messageId;
    private String pagwId;
    private String idempotencyKey;
    private String schemaVersion;
    
    // Routing
    private String stage;
    private String eventType;
    private String sourceService;
    private String targetService;
    private String tenant;
    private String source;  // Backwards compatibility alias for sourceService
    
    // S3 payload pointers
    private String payloadBucket;
    private String payloadKey;
    private String payload;  // For small inline payloads
    private PayloadPointer payloadPointer;  // Structured payload pointer
    private String parsedDataS3Path;  // S3 path to extracted FHIR data (ParsedFhirData JSON)
    
    // Attachment info
    private Boolean hasAttachments;
    private Integer attachmentCount;
    private String attachmentMetadataKey;
    
    // Downstream system info
    private String targetSystem;
    private String externalReferenceId;
    private String apiResponseStatus;
    private Boolean isAsyncResponse;
    
    // Error handling
    private String errorCode;
    private String errorMessage;
    
    // Enrichment tracking
    private List<String> enrichmentSources;
    
    // Metadata
    private Map<String, Object> metadata;
    private Map<String, String> headers;
    private MessageMeta meta;  // Structured metadata
    
    // Timestamps
    private Instant createdAt;
    private Instant timestamp;
    
    // Tracing
    private String correlationId;
    private int attemptNumber;

    /**
     * Factory method for creating a new message.
     */
    public static PagwMessage create(String pagwId, String eventType, 
                                      String sourceService, String payload) {
        return PagwMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .pagwId(pagwId)
                .eventType(eventType)
                .sourceService(sourceService)
                .payload(payload)
                .schemaVersion("v1")
                .timestamp(Instant.now())
                .createdAt(Instant.now())
                .correlationId(pagwId)
                .attemptNumber(1)
                .build();
    }
    
    /**
     * Factory method for creating a message with S3 pointer.
     */
    public static PagwMessage createWithS3Pointer(String pagwId, String stage, 
                                                   String bucket, String key) {
        return PagwMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .pagwId(pagwId)
                .stage(stage)
                .payloadBucket(bucket)
                .payloadKey(key)
                .schemaVersion("v1")
                .createdAt(Instant.now())
                .correlationId(pagwId)
                .attemptNumber(1)
                .build();
    }
    
    /**
     * Get hasAttachments with null safety.
     */
    public Boolean getHasAttachments() {
        return hasAttachments != null ? hasAttachments : false;
    }
    
    /**
     * Get attachmentCount with null safety.
     */
    public Integer getAttachmentCount() {
        return attachmentCount != null ? attachmentCount : 0;
    }

    /**
     * Structured S3 payload pointer.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayloadPointer {
        private String s3Bucket;
        private String s3Key;
        private String contentType;
        private Long contentLength;
    }

    /**
     * Structured message metadata.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageMeta {
        private String tenant;
        private String correlationId;
        private String requestType;
        private Instant receivedAt;
        private Instant processedAt;
        private String authenticatedProviderId;
        private Map<String, String> customAttributes;
    }
}
