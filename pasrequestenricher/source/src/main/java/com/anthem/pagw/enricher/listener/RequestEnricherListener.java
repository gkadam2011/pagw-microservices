package com.anthem.pagw.enricher.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.EventTracker;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.enricher.service.RequestEnricherService;
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
 * SQS Listener for pagw-queue-request-enricher.
 * Enriches claims with external data (eligibility, provider info, etc.).
 */
@Component
public class RequestEnricherListener {

    private static final Logger log = LoggerFactory.getLogger(RequestEnricherListener.class);

    private final RequestEnricherService enricherService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final EventTrackerService eventTrackerService;
    private final OutboxService outboxService;
    private final String nextQueueName;

    public RequestEnricherListener(
            RequestEnricherService enricherService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            EventTrackerService eventTrackerService,
            OutboxService outboxService,
            @Value("${pagw.aws.sqs.request-converter-queue}") String nextQueueName) {
        this.enricherService = enricherService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.eventTrackerService = eventTrackerService;
        this.outboxService = outboxService;
        this.nextQueueName = nextQueueName;
    }

    @SqsListener(value = "${pagw.aws.sqs.request-enricher-queue}")
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
            
            log.info("Received message for enrichment: pagwId={}, stage={}", 
                    pagwId, message.getStage());
            
            // Event tracking: ENRICH_START
            eventTrackerService.logStageStart(pagwId, tenant, "ENRICHER", EventTracker.EVENT_ENRICH_START, null);
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "ENRICHING", "request-enricher");
            
            // Fetch validated data from S3
            String validatedData = s3Service.getObject(
                    message.getPayloadBucket(), 
                    message.getPayloadKey()
            );
            
            // Enrich the claim data
            var enrichedResult = enricherService.enrich(validatedData, message);
            
            // Store enriched data in S3 using standardized path
            String enrichedKey = PagwProperties.S3Paths.enriched(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    enrichedKey,
                    JsonUtils.toJson(enrichedResult.getEnrichedData())
            );
            
            // Prepare next stage message
            PagwMessage nextMessage = PagwMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .pagwId(pagwId)
                    .schemaVersion(message.getSchemaVersion())
                    .stage("CANONICAL_MAPPER")
                    .tenant(message.getTenant())
                    .payloadBucket(message.getPayloadBucket())
                    .payloadKey(enrichedKey)
                    .metadata(message.getMetadata())
                    .enrichmentSources(enrichedResult.getSourcesUsed())
                    .createdAt(Instant.now())
                    .build();
            
            // Write to outbox
            outboxService.writeOutbox(nextQueueName, nextMessage);
            
            // Update tracker
            trackerService.updateStatus(pagwId, "ENRICHED", "request-enricher");
            
            // Event tracking: ENRICH_OK
            long duration = System.currentTimeMillis() - startTime;
            String metadata = String.format("{\"sourcesUsed\":\"%s\",\"sourceCount\":%d}",
                String.join(",", enrichedResult.getSourcesUsed()), enrichedResult.getSourcesUsed().size());
            eventTrackerService.logStageComplete(
                pagwId, tenant, "ENRICHER", EventTracker.EVENT_ENRICH_OK, duration, metadata
            );
            
            log.info("Enrichment complete: pagwId={}, sourcesUsed={}", 
                    pagwId, enrichedResult.getSourcesUsed());
            
        } catch (Exception e) {
            log.error("Error processing message: pagwId={}", pagwId, e);
            if (pagwId != null) {
                // Event tracking: ENRICH_FAIL (exception)
                eventTrackerService.logStageError(
                    pagwId, tenant, "ENRICHER", EventTracker.EVENT_ENRICH_FAIL,
                    "ENRICHMENT_EXCEPTION", e.getMessage(), true, Instant.now().plusSeconds(300)
                );
                
                trackerService.updateStatus(pagwId, "ENRICHMENT_ERROR", "request-enricher",
                        "EXCEPTION", e.getMessage());
            }
            throw new RuntimeException("Failed to process message", e);
        }
    }
}
