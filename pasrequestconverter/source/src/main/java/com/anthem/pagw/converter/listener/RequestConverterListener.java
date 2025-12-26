package com.anthem.pagw.converter.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.EventTracker;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.converter.service.RequestConverterService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SQS Listener for pagw-queue-request-converter.
 * Converts enriched data to per-target payloads for downstream APIs.
 */
@Component
public class RequestConverterListener {

    private static final Logger log = LoggerFactory.getLogger(RequestConverterListener.class);

    private final RequestConverterService converterService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final EventTrackerService eventTrackerService;
    private final OutboxService outboxService;
    private final String nextQueueName;

    public RequestConverterListener(
            RequestConverterService converterService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            EventTrackerService eventTrackerService,
            OutboxService outboxService,
            @Value("${pagw.aws.sqs.api-connector-queue}") String nextQueueName) {
        this.converterService = converterService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.eventTrackerService = eventTrackerService;
        this.outboxService = outboxService;
        this.nextQueueName = nextQueueName;
    }

    @SqsListener(value = "${pagw.aws.sqs.request-converter-queue}")
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
            
            log.info("Received message for conversion: pagwId={}, stage={}", 
                    pagwId, message.getStage());
            
            // Event tracking: CONVERT_START
            eventTrackerService.logStageStart(pagwId, tenant, "CONVERTER", EventTracker.EVENT_CONVERT_START, null);
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "CONVERTING", "request-converter");
            
            // Fetch enriched data from S3
            String enrichedData = s3Service.getObject(
                    message.getPayloadBucket(), 
                    message.getPayloadKey()
            );
            
            // Convert to per-target payload
            var conversionResult = converterService.convertPayload(enrichedData, message);
            
            // Store converted payload in S3 using standardized path
            String convertedKey = PagwProperties.S3Paths.canonical(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    convertedKey,
                    JsonUtils.toJson(conversionResult.getConvertedPayload())
            );
            
            // Prepare next stage message
            PagwMessage nextMessage = PagwMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .pagwId(pagwId)
                    .schemaVersion(message.getSchemaVersion())
                    .stage("API_CONNECTOR")
                    .tenant(message.getTenant())
                    .payloadBucket(message.getPayloadBucket())
                    .payloadKey(convertedKey)
                    .targetSystem(conversionResult.getTargetSystem())
                    .metadata(message.getMetadata())
                    .createdAt(Instant.now())
                    .build();
            
            // Write to outbox
            outboxService.writeOutbox(nextQueueName, nextMessage);
            
            // Update tracker
            trackerService.updateStatus(pagwId, "CONVERTED", "request-converter");
            
            // Event tracking: CONVERT_OK
            long duration = System.currentTimeMillis() - startTime;
            String metadata = String.format("{\"targetSystem\":\"%s\"}",
                conversionResult.getTargetSystem());
            eventTrackerService.logStageComplete(
                pagwId, tenant, "CONVERTER", EventTracker.EVENT_CONVERT_OK, duration, metadata
            );
            
            log.info("Converted successfully: pagwId={}, targetSystem={}", 
                    pagwId, conversionResult.getTargetSystem());
            
        } catch (Exception e) {
            log.error("Conversion error: pagwId={}", pagwId, e);
            
            if (pagwId != null) {
                // Event tracking: CONVERT_FAIL (exception)
                eventTrackerService.logStageError(
                    pagwId, tenant, "CONVERTER", EventTracker.EVENT_CONVERT_FAIL,
                    "CONVERSION_EXCEPTION", e.getMessage(), true, Instant.now().plusSeconds(300)
                );
                
                trackerService.updateError(pagwId, "CONVERSION_ERROR", 
                        e.getMessage(), "request-converter");
            }
            
            throw new RuntimeException("Conversion failed: " + pagwId, e);
        }
    }
}
