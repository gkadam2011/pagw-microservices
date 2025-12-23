package com.anthem.pagw.connector.listener;

import com.anthem.pagw.connector.client.ExternalApiClient;
import com.anthem.pagw.connector.model.ApiResponse;
import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
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
 * SQS Listener for pagw-queue-api-connectors.
 * Connects to external claims processing APIs (Carelon, EAPI, etc).
 * This can run on Fargate for long-lived connections.
 * 
 * Flow per target-architecture:
 * - Connector submits to external API
 * - Connector always sends to callback-handler (for both sync and async responses)
 * - CallbackHandler normalizes and forwards to response-builder
 */
@Component
public class ApiConnectorListener {

    private static final Logger log = LoggerFactory.getLogger(ApiConnectorListener.class);

    private final ExternalApiClient externalApiClient;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final OutboxService outboxService;
    private final String callbackQueueName;

    public ApiConnectorListener(
            ExternalApiClient externalApiClient,
            S3Service s3Service,
            RequestTrackerService trackerService,
            OutboxService outboxService,
            @Value("${pagw.aws.sqs.callback-handler-queue}") String callbackQueueName) {
        this.externalApiClient = externalApiClient;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.outboxService = outboxService;
        this.callbackQueueName = callbackQueueName;
    }

    @SqsListener(value = "${pagw.aws.sqs.api-connectors-queue}")
    @Transactional
    public void handleMessage(
            String messageBody,
            @Header(value = "pagwId", required = false) String pagwIdHeader) {
        
        PagwMessage message = null;
        String pagwId = null;
        
        try {
            message = JsonUtils.fromJson(messageBody, PagwMessage.class);
            pagwId = message.getPagwId();
            
            log.info("Received message for API submission: pagwId={}, targetSystem={}", 
                    pagwId, message.getTargetSystem());
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "SUBMITTING", "api-connector");
            
            // Fetch converted payload from S3
            String convertedPayload = s3Service.getObject(
                    message.getPayloadBucket(), 
                    message.getPayloadKey()
            );
            
            // Submit to target external system
            ApiResponse apiResponse = externalApiClient.submitToExternalSystem(
                    message.getTargetSystem(), 
                    convertedPayload,
                    message
            );
            
            // Store API response in S3 using standardized path
            String responseKey = PagwProperties.S3Paths.payerResponse(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    responseKey,
                    JsonUtils.toJson(apiResponse)
            );
            
            // Update tracker based on response type
            if (apiResponse.isAsync()) {
                trackerService.updateStatus(pagwId, "AWAITING_CALLBACK", "api-connector");
            } else {
                trackerService.updateStatus(pagwId, "SUBMITTED", "api-connector");
            }
            
            // Update external reference in tracker
            trackerService.updateExternalReference(pagwId, apiResponse.getExternalId());
            
            // Per target-architecture: Connector always sends to callback-handler
            // CallbackHandler normalizes both sync and async responses before forwarding to response-builder
            PagwMessage callbackMessage = PagwMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .pagwId(pagwId)
                    .schemaVersion(message.getSchemaVersion())
                    .stage("CALLBACK_HANDLER")
                    .tenant(message.getTenant())
                    .payloadBucket(message.getPayloadBucket())
                    .payloadKey(responseKey)
                    .targetSystem(message.getTargetSystem())
                    .externalReferenceId(apiResponse.getExternalId())
                    .apiResponseStatus(apiResponse.getStatus())
                    .isAsyncResponse(apiResponse.isAsync())
                    .metadata(message.getMetadata())
                    .createdAt(Instant.now())
                    .build();
            
            // Write to outbox - always route to callback-handler
            outboxService.writeOutbox(callbackQueueName, callbackMessage);
            
            log.info("API submission complete: pagwId={}, externalId={}, async={}, routedTo=callback-handler", 
                    pagwId, apiResponse.getExternalId(), apiResponse.isAsync());
            
        } catch (Exception e) {
            log.error("API connector error: pagwId={}", pagwId, e);
            
            if (pagwId != null) {
                trackerService.updateError(pagwId, "API_CONNECTOR_ERROR", 
                        e.getMessage(), "api-connector");
            }
            
            throw new RuntimeException("API connector failed: " + pagwId, e);
        }
    }
}
