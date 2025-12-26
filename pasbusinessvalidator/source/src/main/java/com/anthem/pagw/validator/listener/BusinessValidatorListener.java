package com.anthem.pagw.validator.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.EventTracker;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.validator.model.ValidationResult;
import com.anthem.pagw.validator.service.BusinessValidatorService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SQS Listener for pagw-queue-business-validator.
 * Validates claims against business rules.
 */
@Component
public class BusinessValidatorListener {

    private static final Logger log = LoggerFactory.getLogger(BusinessValidatorListener.class);
    // AWS SQS queue names (include environment prefix and .fifo suffix)
    private static final String NEXT_QUEUE = "dev-PAGW-pagw-request-enricher-queue.fifo";
    private static final String ERROR_QUEUE = "dev-PAGW-pagw-response-builder-queue.fifo";

    private final BusinessValidatorService validatorService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final EventTrackerService eventTrackerService;
    private final OutboxService outboxService;

    public BusinessValidatorListener(
            BusinessValidatorService validatorService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            EventTrackerService eventTrackerService,
            OutboxService outboxService) {
        this.validatorService = validatorService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.eventTrackerService = eventTrackerService;
        this.outboxService = outboxService;
    }

    @SqsListener(value = "${pagw.aws.sqs.business-validator-queue}")
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
            tenant = message.getEffectiveTenant();
            
            log.info("Received message for validation: pagwId={}, stage={}", 
                    pagwId, message.getStage());
            
            // Event tracking: VAL_START
            eventTrackerService.logStageStart(pagwId, tenant, "VALIDATOR", EventTracker.EVENT_VAL_START, null);
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "VALIDATING", "business-validator");
            
            // Fetch enriched data from S3
            String enrichedData = s3Service.getObject(
                    message.getPayloadBucket(), 
                    message.getPayloadKey()
            );
            
            // Run business validation
            ValidationResult result = validatorService.validate(enrichedData, message);
            
            // Store validation result using standardized path
            String validationKey = PagwProperties.S3Paths.validated(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    validationKey,
                    JsonUtils.toJson(result)
            );
            
            if (!result.isValid()) {
                log.warn("Validation failed for pagwId={}: {} errors", 
                        pagwId, result.getErrors().size());
                
                // Event tracking: VAL_FAIL
                String errorDetails = result.getSummary();
                String metadata = String.format("{\"errorCount\":%d,\"warningCount\":%d}",
                    result.getErrors().size(), result.getWarnings().size());
                eventTrackerService.logStageError(
                    pagwId, tenant, "VALIDATOR", EventTracker.EVENT_VAL_FAIL,
                    "VALIDATION_FAILED", errorDetails, false, null
                );
                
                trackerService.updateStatus(pagwId, "VALIDATION_FAILED", "business-validator",
                        "VALIDATION_ERROR", result.getSummary());
                
                // Send to response builder for error response
                PagwMessage errorMessage = PagwMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .pagwId(pagwId)
                        .schemaVersion(message.getSchemaVersion())
                        .stage("RESPONSE_BUILDER")
                        .tenant(message.getTenant())
                        .payloadBucket(message.getPayloadBucket())
                        .payloadKey(validationKey)
                        .errorCode("VALIDATION_FAILED")
                        .errorMessage(result.getSummary())
                        .metadata(message.getMetadata())
                        .createdAt(Instant.now())
                        .build();
                
                outboxService.writeOutbox(ERROR_QUEUE, errorMessage);
                return;
            }
            
            // Prepare next stage message
            PagwMessage nextMessage = PagwMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .pagwId(pagwId)
                    .schemaVersion(message.getSchemaVersion())
                    .stage("REQUEST_ENRICHER")
                    .tenant(message.getTenant())
                    .payloadBucket(message.getPayloadBucket())
                    .payloadKey(validationKey)
                    .metadata(message.getMetadata())
                    .createdAt(Instant.now())
                    .build();
            
            // Write to outbox
            outboxService.writeOutbox(NEXT_QUEUE, nextMessage);
            
            // Update tracker
            trackerService.updateStatus(pagwId, "VALIDATED", "business-validator");
            
            // Event tracking: VAL_OK
            long duration = System.currentTimeMillis() - startTime;
            String metadata = String.format("{\"errorCount\":0,\"warningCount\":%d}",
                result.getWarnings().size());
            eventTrackerService.logStageComplete(
                pagwId, tenant, "VALIDATOR", EventTracker.EVENT_VAL_OK, duration, metadata
            );
            
            log.info("Validation complete: pagwId={}, valid={}, warnings={}", 
                    pagwId, result.isValid(), result.getWarnings().size());
            
        } catch (Exception e) {
            log.error("Error processing message: pagwId={}", pagwId, e);
            if (pagwId != null) {
                // Event tracking: VAL_FAIL (exception)
                eventTrackerService.logStageError(
                    pagwId, tenant, "VALIDATOR", EventTracker.EVENT_VAL_FAIL,
                    "VALIDATION_EXCEPTION", e.getMessage(), true, Instant.now().plusSeconds(300)
                );
                
                trackerService.updateStatus(pagwId, "VALIDATION_ERROR", "business-validator",
                        "EXCEPTION", e.getMessage());
            }
            throw new RuntimeException("Failed to process message", e);
        }
    }
}
