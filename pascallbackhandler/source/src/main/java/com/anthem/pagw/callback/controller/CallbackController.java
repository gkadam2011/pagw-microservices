package com.anthem.pagw.callback.controller;

import com.anthem.pagw.callback.model.CallbackRequest;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for receiving callbacks from downstream systems.
 */
@RestController
@RequestMapping("/api/v1/callbacks")
public class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);
    private static final String CALLBACK_QUEUE = "pagw-callback-handler-queue";

    private final RequestTrackerService trackerService;
    private final OutboxService outboxService;

    public CallbackController(
            RequestTrackerService trackerService,
            OutboxService outboxService) {
        this.trackerService = trackerService;
        this.outboxService = outboxService;
    }

    /**
     * Receive callback from downstream system.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> receiveCallback(
            @RequestBody CallbackRequest callback,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        
        log.info("Received callback: referenceId={}, pagwId={}, status={}", 
                callback.getReferenceId(), callback.getPagwId(), callback.getStatus());
        
        callback.setReceivedAt(Instant.now());
        
        // Look up the original request if pagwId is not provided
        String pagwId = callback.getPagwId();
        if (pagwId == null && callback.getReferenceId() != null) {
            pagwId = trackerService.findByExternalReference(callback.getReferenceId());
        }
        
        if (pagwId == null) {
            log.warn("Could not find PAGW ID for callback: referenceId={}", callback.getReferenceId());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Could not find associated request"
            ));
        }
        
        // Queue for processing
        PagwMessage message = PagwMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .pagwId(pagwId)
                .schemaVersion("v1")
                .stage("CALLBACK_HANDLER")
                .externalReferenceId(callback.getReferenceId())
                .apiResponseStatus(callback.getStatus())
                .createdAt(Instant.now())
                .build();
        
        outboxService.writeOutbox(CALLBACK_QUEUE, message);
        
        // Update tracker
        trackerService.updateStatus(pagwId, "CALLBACK_RECEIVED", "callback-controller");
        
        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "pagwId", pagwId,
                "message", "Callback received and queued for processing"
        ));
    }

    /**
     * Webhook endpoint for downstream systems.
     */
    @PostMapping("/webhook/{targetSystem}")
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @PathVariable String targetSystem,
            @RequestBody String rawPayload,
            @RequestHeader Map<String, String> headers) {
        
        log.info("Received webhook from {}: payload length={}", targetSystem, rawPayload.length());
        
        try {
            // Parse the raw payload
            var payload = JsonUtils.parseJson(rawPayload);
            
            // Extract common fields based on target system
            String referenceId = payload.path("referenceId").asText(
                    payload.path("claimId").asText(
                            payload.path("id").asText()
                    ));
            String status = payload.path("status").asText("UNKNOWN");
            
            // Create callback request
            CallbackRequest callback = new CallbackRequest();
            callback.setReferenceId(referenceId);
            callback.setStatus(status);
            callback.setReceivedAt(Instant.now());
            
            return receiveCallback(callback, headers.get("x-correlation-id"));
            
        } catch (Exception e) {
            log.error("Failed to process webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Failed to parse webhook payload"
            ));
        }
    }
}
