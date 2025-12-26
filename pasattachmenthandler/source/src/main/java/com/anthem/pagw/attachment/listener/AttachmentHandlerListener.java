package com.anthem.pagw.attachment.listener;

import com.anthem.pagw.attachment.model.AttachmentInfo;
import com.anthem.pagw.attachment.service.AttachmentHandlerService;
import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.EventTracker;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SQS Listener for pagw-queue-attachment-handler.
 * Processes claim attachments (documents, images, etc.).
 * 
 * Flow per target-architecture:
 * - This is a PARALLEL PATH triggered by RequestParser when attachments exist.
 * - AttachmentHandler processes attachments and stores metadata in S3.
 * - AttachmentHandler does NOT route to another queue (it's a side effect, not main flow).
 * - The main flow continues: Parser → BusinessValidator → Enricher → Converter → API-Connector
 */
@Component
public class AttachmentHandlerListener {

    private static final Logger log = LoggerFactory.getLogger(AttachmentHandlerListener.class);

    private final AttachmentHandlerService attachmentService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final EventTrackerService eventTrackerService;

    public AttachmentHandlerListener(
            AttachmentHandlerService attachmentService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            EventTrackerService eventTrackerService) {
        this.attachmentService = attachmentService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.eventTrackerService = eventTrackerService;
    }

    @SqsListener(value = "${pagw.aws.sqs.attachment-handler-queue}")
    @Transactional
    public void handleMessage(
            String messageBody,
            @Header(value = "pagwId", required = false) String pagwIdHeader) {
        
        PagwMessage message = null;
        String pagwId = null;
        String tenant = null;
        long startTime = System.currentTimeMillis();
        
        try {
            message = JsonUtils.fromJson(messageBody, PagwMessage.class);
            pagwId = message.getPagwId();
            tenant = message.getTenant();
            
            log.info("Received message for attachment processing: pagwId={}, attachmentCount={}", 
                    pagwId, message.getAttachmentCount());
            
            // Event tracking: ATTACH_START
            eventTrackerService.logStageStart(pagwId, tenant, "ATTACHMENT_HANDLER", EventTracker.EVENT_ATTACH_START, null);
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "PROCESSING_ATTACHMENTS", "attachment-handler");
            
            // Fetch parsed data from S3
            String parsedData = s3Service.getObject(
                    message.getPayloadBucket(), 
                    message.getPayloadKey()
            );
            
            // Process attachments
            List<AttachmentInfo> processedAttachments = List.of();
            if (Boolean.TRUE.equals(message.getHasAttachments()) && message.getAttachmentCount() > 0) {
                processedAttachments = attachmentService.processAttachments(parsedData, message);
                
                log.info("Processed {} attachments for pagwId={}", 
                        processedAttachments.size(), pagwId);
            }
            
            // Store attachment metadata in S3 using standardized path
            String attachmentKey = PagwProperties.S3Paths.attachmentMeta(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    attachmentKey,
                    JsonUtils.toJson(new AttachmentResult(pagwId, processedAttachments))
            );
            
            // Update tracker - attachment processing is complete (parallel path ends here)
            trackerService.updateStatus(pagwId, "ATTACHMENTS_PROCESSED", "attachment-handler");
            
            // Event tracking: ATTACH_OK
            long duration = System.currentTimeMillis() - startTime;
            String metadata = String.format("{\"attachmentCount\":%d}",
                processedAttachments.size());
            eventTrackerService.logStageComplete(
                pagwId, tenant, "ATTACHMENT_HANDLER", EventTracker.EVENT_ATTACH_OK, duration, metadata
            );
            
            log.info("Attachment processing complete: pagwId={}, attachmentCount={} (parallel path complete, main flow continues independently)", 
                    pagwId, processedAttachments.size());
            
            // NOTE: No next queue routing - this is a parallel side path.
            // Main flow: Parser → BusinessValidator → Enricher → Converter → API-Connector
            // Attachment metadata is stored in S3 for later consumption if needed.
            
        } catch (Exception e) {
            log.error("Error processing attachments: pagwId={}", pagwId, e);
            if (pagwId != null) {
                // Event tracking: ATTACH_FAIL (exception)
                eventTrackerService.logStageError(
                    pagwId, tenant, "ATTACHMENT_HANDLER", EventTracker.EVENT_ATTACH_FAIL,
                    "ATTACHMENT_EXCEPTION", e.getMessage(), true, java.time.Instant.now().plusSeconds(300)
                );
                
                trackerService.updateError(pagwId, "ATTACHMENT_ERROR", e.getMessage(), "attachment-handler");
            }
            throw new RuntimeException("Failed to process attachments", e);
        }
    }
    
    /**
     * Simple result wrapper for attachment processing.
     */
    private static class AttachmentResult {
        private final String pagwId;
        private final List<AttachmentInfo> attachments;
        
        public AttachmentResult(String pagwId, List<AttachmentInfo> attachments) {
            this.pagwId = pagwId;
            this.attachments = attachments;
        }
        
        public String getPagwId() { return pagwId; }
        public List<AttachmentInfo> getAttachments() { return attachments; }
    }
}
