package com.anthem.pagw.callback.listener;

import com.anthem.pagw.callback.model.CallbackRequest;
import com.anthem.pagw.callback.service.CallbackHandlerService;
import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SQS Listener for pagw-queue-callback-handler.
 * Handles callbacks from downstream systems for async processing.
 */
@Component
public class CallbackHandlerListener {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandlerListener.class);
    // AWS SQS queue names (include environment prefix and .fifo suffix)
    private static final String RESPONSE_QUEUE = "dev-PAGW-pagw-response-builder-queue.fifo";

    private final CallbackHandlerService callbackService;
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final OutboxService outboxService;

    public CallbackHandlerListener(
            CallbackHandlerService callbackService,
            S3Service s3Service,
            RequestTrackerService trackerService,
            OutboxService outboxService) {
        this.callbackService = callbackService;
        this.s3Service = s3Service;
        this.trackerService = trackerService;
        this.outboxService = outboxService;
    }

    @SqsListener(value = "${pagw.aws.sqs.callback-handler-queue}")
    @Transactional
    public void handleMessage(
            String messageBody,
            @Header(value = "pagwId", required = false) String pagwIdHeader) {
        
        PagwMessage message = null;
        String pagwId = null;
        
        try {
            message = JsonUtils.fromJson(messageBody, PagwMessage.class);
            pagwId = message.getPagwId();
            
            log.info("Received callback message: pagwId={}, externalRef={}", 
                    pagwId, message.getExternalReferenceId());
            
            // Update tracker status
            trackerService.updateStatus(pagwId, "PROCESSING_CALLBACK", "callback-handler");
            
            // Fetch original response data
            String responseData = s3Service.getObject(
                    message.getPayloadBucket(), 
                    message.getPayloadKey()
            );
            
            // Process the callback
            var callbackResult = callbackService.processCallback(responseData, message);
            
            // Store callback result using standardized path
            String callbackKey = PagwProperties.S3Paths.callbackResponse(pagwId);
            s3Service.putObject(
                    message.getPayloadBucket(),
                    callbackKey,
                    JsonUtils.toJson(callbackResult)
            );
            
            // Prepare message for response builder
            PagwMessage responseMessage = PagwMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .pagwId(pagwId)
                    .schemaVersion(message.getSchemaVersion())
                    .stage("RESPONSE_BUILDER")
                    .tenant(message.getTenant())
                    .payloadBucket(message.getPayloadBucket())
                    .payloadKey(callbackKey)
                    .externalReferenceId(message.getExternalReferenceId())
                    .apiResponseStatus(callbackResult.getStatus())
                    .metadata(message.getMetadata())
                    .createdAt(Instant.now())
                    .build();
            
            // Check if callback indicates error
            if (callbackResult.isError()) {
                responseMessage = responseMessage.toBuilder()
                        .errorCode(callbackResult.getErrorCode())
                        .errorMessage(callbackResult.getErrorMessage())
                        .build();
            }
            
            // Write to outbox
            outboxService.writeOutbox(RESPONSE_QUEUE, responseMessage);
            
            // Update tracker
            trackerService.updateStatus(pagwId, "CALLBACK_PROCESSED", "callback-handler");
            
            log.info("Callback processing complete: pagwId={}, status={}", 
                    pagwId, callbackResult.getStatus());
            
        } catch (Exception e) {
            log.error("Error processing callback: pagwId={}", pagwId, e);
            if (pagwId != null) {
                trackerService.updateStatus(pagwId, "CALLBACK_ERROR", "callback-handler",
                        "EXCEPTION", e.getMessage());
            }
            throw new RuntimeException("Failed to process callback", e);
        }
    }
}
