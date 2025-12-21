package com.anthem.pagw.response.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.response.model.ClaimResponse;
import com.anthem.pagw.response.service.ResponseBuilderService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQS Listener for pagw-queue-response-builder.
 * Builds FHIR ClaimResponse for providers, then routes to subscription handler.
 */
@Component
public class ResponseBuilderListener {

    private static final Logger log = LoggerFactory.getLogger(ResponseBuilderListener.class);
    private static final String NEXT_QUEUE = "pagw-subscription-handler-queue";

    private final ResponseBuilderService responseBuilderService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final OutboxService outboxService;

    public ResponseBuilderListener(
            ResponseBuilderService responseBuilderService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            OutboxService outboxService) {
        this.responseBuilderService = responseBuilderService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.outboxService = outboxService;
    }

    @SqsListener(value = "${pagw.aws.sqs.response-builder-queue}")
    @Transactional
    public void handleMessage(
            String messageBody,
            @Header(value = "pagwId", required = false) String pagwIdHeader) {
        
        PagwMessage message = null;
        String pagwId = null;
        
        try {
            message = JsonUtils.fromJson(messageBody, PagwMessage.class);
            pagwId = message.getPagwId();
            
            log.info("Received message for response building: pagwId={}, stage={}", 
                    pagwId, message.getStage());
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "BUILDING_RESPONSE", "response-builder");
            
            // Fetch API response data from S3
            String responseData = null;
            if (message.getPayloadKey() != null) {
                try {
                    responseData = s3Service.getObject(
                            message.getPayloadBucket(), 
                            message.getPayloadKey()
                    );
                } catch (Exception e) {
                    log.warn("Could not fetch response data: pagwId={}", pagwId);
                }
            }
            
            // Build FHIR ClaimResponse
            ClaimResponse claimResponse;
            if (message.getErrorCode() != null) {
                // Error response
                claimResponse = responseBuilderService.buildErrorResponse(message);
            } else {
                // Success response
                claimResponse = responseBuilderService.buildSuccessResponse(responseData, message);
            }
            
            // Store final FHIR ClaimResponse in S3 using standardized path
            String finalKey = PagwProperties.S3Paths.fhirResponse(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    finalKey,
                    JsonUtils.toJson(claimResponse)
            );
            
            // Update tracker with final status
            String finalStatus = claimResponse.getOutcome().equals("complete") ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
            trackerService.updateFinalStatus(pagwId, finalStatus, "response-builder", 
                    message.getPayloadBucket(), finalKey);
            
            // Route to subscription handler for webhook notifications
            PagwMessage nextMessage = message.toBuilder()
                    .stage("SUBSCRIPTION_HANDLER")
                    .payloadKey(finalKey)
                    .apiResponseStatus(claimResponse.getOutcome())
                    .build();
            outboxService.writeOutbox(NEXT_QUEUE, nextMessage);
            
            log.info("Response building complete: pagwId={}, outcome={}, disposition={}, routedTo={}", 
                    pagwId, claimResponse.getOutcome(), claimResponse.getDisposition(), NEXT_QUEUE);
            
        } catch (Exception e) {
            log.error("Error processing message: pagwId={}", pagwId, e);
            if (pagwId != null) {
                trackerService.updateStatus(pagwId, "RESPONSE_BUILD_ERROR", "response-builder",
                        "EXCEPTION", e.getMessage());
            }
            throw new RuntimeException("Failed to process message", e);
        }
    }
}
