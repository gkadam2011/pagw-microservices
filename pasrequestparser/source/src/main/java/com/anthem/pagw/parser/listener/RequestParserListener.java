package com.anthem.pagw.parser.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.EventTracker;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.model.fhir.ParsedFhirData;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.FhirExtractionService;
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
    private final FhirExtractionService fhirExtractionService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final EventTrackerService eventTrackerService;
    private final OutboxService outboxService;

    public RequestParserListener(
            RequestParserService parserService,
            FhirExtractionService fhirExtractionService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            EventTrackerService eventTrackerService,
            OutboxService outboxService) {
        this.parserService = parserService;
        this.fhirExtractionService = fhirExtractionService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.eventTrackerService = eventTrackerService;
        this.outboxService = outboxService;
    }

    @SqsListener(value = "${pagw.aws.sqs.request-parser-queue}")
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
            
            log.info("Received message for parsing: pagwId={}, stage={}", 
                    pagwId, message.getStage());
            
            // Event tracking: PARSE_START
            eventTrackerService.logStageStart(pagwId, tenant, "PARSER", EventTracker.EVENT_PARSE_START, null);
            
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
                
                // Event tracking: PARSE_FAIL
                String errorMessage = String.join("; ", parseResult.getErrors());
                eventTrackerService.logStageError(
                    pagwId, tenant, "PARSER", EventTracker.EVENT_PARSE_FAIL,
                    "PARSE_VALIDATION_ERROR", errorMessage, false, null
                );
                
                trackerService.updateError(pagwId, "PARSE_FAILED", errorMessage, "request-parser");
                return;
            }
            
            // Extract structured FHIR data (Round 3)
            ParsedFhirData parsedFhirData = null;
            String parsedDataS3Path = null;
            try {
                parsedFhirData = fhirExtractionService.extractFromBundle(rawBundle, pagwId, tenant);
                
                // Store parsed data to S3
                parsedDataS3Path = s3Service.putParsedData(
                    message.getPayloadBucket(),
                    tenant,
                    pagwId,
                    JsonUtils.toJson(parsedFhirData)
                );
                
                log.info("FHIR extraction complete: pagwId={}, patient={}, diagnosisCount={}, procedureCount={}, urgent={}", 
                    pagwId,
                    parsedFhirData.getPatient() != null ? parsedFhirData.getPatient().getMemberId() : "null",
                    parsedFhirData.getTotalDiagnosisCodes(),
                    parsedFhirData.getTotalProcedureCodes(),
                    parsedFhirData.isHasUrgentIndicator());
            } catch (Exception e) {
                log.warn("FHIR extraction failed (non-blocking): pagwId={}, error={}", pagwId, e.getMessage());
                // Continue processing - extraction failure is not fatal
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
                        .parsedDataS3Path(parsedDataS3Path)
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
                    .parsedDataS3Path(parsedDataS3Path)
                    .hasAttachments(parseResult.hasAttachments())
                    .attachmentCount(parseResult.getAttachmentCount())
                    .metadata(message.getMetadata())
                    .createdAt(Instant.now())
                    .build();
            
            outboxService.writeOutbox(BUSINESS_VALIDATOR_QUEUE, businessMessage);
            
            // Event tracking: PARSE_OK
            long duration = System.currentTimeMillis() - startTime;
            String metadata = parsedFhirData != null 
                ? String.format("{\"attachmentCount\":%d,\"hasAttachments\":%b,\"diagnosisCount\":%d,\"procedureCount\":%d,\"hasUrgent\":%b}",
                    parseResult.getAttachmentCount(), parseResult.hasAttachments(),
                    parsedFhirData.getTotalDiagnosisCodes(), parsedFhirData.getTotalProcedureCodes(), parsedFhirData.isHasUrgentIndicator())
                : String.format("{\"attachmentCount\":%d,\"hasAttachments\":%b}",
                    parseResult.getAttachmentCount(), parseResult.hasAttachments());
            eventTrackerService.logStageComplete(
                pagwId, tenant, "PARSER", EventTracker.EVENT_PARSE_OK, duration, metadata
            );
            
            log.info("Parse complete: pagwId={}, sentTo=business-validator, hasAttachments={}", 
                    pagwId, parseResult.hasAttachments());
            
        } catch (Exception e) {
            log.error("Error processing message: pagwId={}", pagwId, e);
            if (pagwId != null) {
                // Event tracking: PARSE_FAIL (exception)
                eventTrackerService.logStageError(
                    pagwId, tenant, "PARSER", EventTracker.EVENT_PARSE_FAIL,
                    "PARSE_EXCEPTION", e.getMessage(), true, Instant.now().plusSeconds(300)
                );
                
                trackerService.updateError(pagwId, "PARSE_EXCEPTION", e.getMessage(), "request-parser");
            }
            throw new RuntimeException("Failed to process message", e);
        }
    }
}
