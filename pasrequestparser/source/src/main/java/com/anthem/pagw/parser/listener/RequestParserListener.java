package com.anthem.pagw.parser.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.parser.service.RequestParserService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SQS Listener for pagw-queue-request-parser.
 * Consumes messages from orchestrator and parses FHIR bundles.
 * 
 * Flow per target-architecture:
 * - Parser sends to business-validator (main flow)
 * - Parser also sends to attachment-handler (if attachments exist - parallel path)
 */
@Component
public class RequestParserListener {

    private static final Logger log = LoggerFactory.getLogger(RequestParserListener.class);
    // AWS SQS queue names (include environment prefix and .fifo suffix)
    private static final String BUSINESS_VALIDATOR_QUEUE = "dev-PAGW-pagw-business-validator-queue.fifo";
    private static final String ATTACHMENT_HANDLER_QUEUE = "dev-PAGW-pagw-attachment-handler-queue.fifo";

    private final RequestParserService parserService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final OutboxService outboxService;

    public RequestParserListener(
            RequestParserService parserService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            OutboxService outboxService) {
        this.parserService = parserService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.outboxService = outboxService;
    }

    @SqsListener(value = "${pagw.aws.sqs.request-parser-queue}")
    @Transactional
    public void handleMessage(
            String messageBody,
            @Header(value = "pagwId", required = false) String pagwIdHeader) {
        
        PagwMessage message = null;
        String pagwId = null;
        
        try {
            message = JsonUtils.fromJson(messageBody, PagwMessage.class);
            pagwId = message.getPagwId();
            
            log.info("Received message for parsing: pagwId={}, stage={}", 
                    pagwId, message.getStage());
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "PARSING", "request-parser");
            
            // Fetch raw bundle from S3
            String rawBundle = s3Service.getObject(
                    message.getPayloadBucket(), 
                    message.getPayloadKey()
            );
            
            // Parse the FHIR bundle
            var parseResult = parserService.parse(rawBundle, message);
            
            if (!parseResult.isValid()) {
                log.error("Parse failed for pagwId={}: {}", pagwId, parseResult.getErrors());
                trackerService.updateError(pagwId, "PARSE_FAILED", 
                        String.join("; ", parseResult.getErrors()), "request-parser");
                return;
            }
            
            // Store parsed result in S3 using standardized path
            String parsedKey = PagwProperties.S3Paths.parsed(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    parsedKey,
                    JsonUtils.toJson(parseResult.getParsedData())
            );
            
            // Update request_tracker with parsed summary
            trackerService.updateStatus(pagwId, "PARSED", "request-parser");
            
            // If attachments exist, send to attachment-handler (parallel path)
            if (parseResult.hasAttachments()) {
                PagwMessage attachmentMessage = PagwMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .pagwId(pagwId)
                        .schemaVersion(message.getSchemaVersion())
                        .stage("ATTACHMENT_HANDLER")
                        .tenant(message.getTenant())
                        .payloadBucket(message.getPayloadBucket())
                        .payloadKey(parsedKey)
                        .hasAttachments(true)
                        .attachmentCount(parseResult.getAttachmentCount())
                        .metadata(message.getMetadata())
                        .createdAt(Instant.now())
                        .build();
                
                outboxService.writeOutbox(ATTACHMENT_HANDLER_QUEUE, attachmentMessage);
                log.info("Sent to attachment-handler: pagwId={}, attachmentCount={}", 
                        pagwId, parseResult.getAttachmentCount());
            }
            
            // Main flow: Send to business-validator
            PagwMessage businessMessage = PagwMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .pagwId(pagwId)
                    .schemaVersion(message.getSchemaVersion())
                    .stage("BUSINESS_VALIDATOR")
                    .tenant(message.getTenant())
                    .payloadBucket(message.getPayloadBucket())
                    .payloadKey(parsedKey)
                    .hasAttachments(parseResult.hasAttachments())
                    .attachmentCount(parseResult.getAttachmentCount())
                    .metadata(message.getMetadata())
                    .createdAt(Instant.now())
                    .build();
            
            outboxService.writeOutbox(BUSINESS_VALIDATOR_QUEUE, businessMessage);
            
            log.info("Parse complete: pagwId={}, sentTo=business-validator, hasAttachments={}", 
                    pagwId, parseResult.hasAttachments());
            
        } catch (Exception e) {
            log.error("Error processing message: pagwId={}", pagwId, e);
            if (pagwId != null) {
                trackerService.updateError(pagwId, "PARSE_EXCEPTION", e.getMessage(), "request-parser");
            }
            throw new RuntimeException("Failed to process message", e);
        }
    }
}
