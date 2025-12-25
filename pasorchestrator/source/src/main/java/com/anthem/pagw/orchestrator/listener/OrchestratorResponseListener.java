package com.anthem.pagw.orchestrator.listener;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.util.JsonUtils;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQS Listener for orchestrator-complete-queue.
 * 
 * Routes async workflow messages to appropriate downstream queues based on stage.
 * This completes the async processing cycle:
 * 1. Request pended by orchestrator → outbox entry created
 * 2. Outbox publisher → orchestrator-complete-queue
 * 3. This listener → routes to next stage queue
 * 4. Stage-specific service processes and continues flow
 * 
 * Note: Uses queue NAMES (not URLs) for outbox entries, consistent with other services.
 * The outbox publisher will resolve queue names to URLs when publishing.
 */
@Component
public class OrchestratorResponseListener {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorResponseListener.class);

    // AWS SQS queue names (include environment prefix and .fifo suffix)
    private static final String REQUEST_PARSER_QUEUE = "dev-PAGW-pagw-request-parser-queue.fifo";
    private static final String BUSINESS_VALIDATOR_QUEUE = "dev-PAGW-pagw-business-validator-queue.fifo";
    private static final String REQUEST_ENRICHER_QUEUE = "dev-PAGW-pagw-request-enricher-queue.fifo";
    private static final String REQUEST_CONVERTER_QUEUE = "dev-PAGW-pagw-request-converter-queue.fifo";
    private static final String API_CONNECTORS_QUEUE = "dev-PAGW-pagw-api-connectors-queue.fifo";

    private final OutboxService outboxService;
    private final RequestTrackerService trackerService;

    public OrchestratorResponseListener(
            OutboxService outboxService,
            RequestTrackerService trackerService) {
        this.outboxService = outboxService;
        this.trackerService = trackerService;
    }

    @SqsListener(value = "${pagw.aws.sqs.response-queue}")
    @Transactional
    public void handleMessage(
            String messageBody,
            @Header(value = "pagwId", required = false) String pagwIdHeader) {

        PagwMessage message = null;
        String pagwId = null;

        try {
            message = JsonUtils.fromJson(messageBody, PagwMessage.class);
            pagwId = message.getPagwId();

            log.info("Orchestrator received async message: pagwId={}, stage={}", 
                    pagwId, message.getStage());

            // Route to appropriate queue based on stage
            String targetQueue = determineTargetQueue(message.getStage());
            
            if (targetQueue == null) {
                log.error("Unknown stage for routing: pagwId={}, stage={}", 
                        pagwId, message.getStage());
                trackerService.updateError(pagwId, "ROUTING_ERROR", 
                        "Unknown stage: " + message.getStage(), "orchestrator");
                return;
            }

            // Update tracker
            trackerService.updateStatus(pagwId, "ROUTING_TO_" + message.getStage(), "orchestrator");

            // Write to outbox for next stage
            outboxService.writeOutbox(targetQueue, message);

            log.info("Routed async message: pagwId={}, stage={}, targetQueue={}", 
                    pagwId, message.getStage(), targetQueue);

        } catch (Exception e) {
            log.error("Error processing orchestrator response: pagwId={}", pagwId, e);
            if (pagwId != null) {
                trackerService.updateError(pagwId, "ORCHESTRATOR_ROUTING_ERROR", 
                        e.getMessage(), "orchestrator");
            }
            throw new RuntimeException("Failed to route message", e);
        }
    }

    /**
     * Determine target queue based on message stage.
     * Returns queue NAME (not URL) for consistency with other services.
     */
    private String determineTargetQueue(String stage) {
        return switch (stage) {
            case "REQUEST_PARSER" -> REQUEST_PARSER_QUEUE;
            case "BUSINESS_VALIDATOR" -> BUSINESS_VALIDATOR_QUEUE;
            case "REQUEST_ENRICHER" -> REQUEST_ENRICHER_QUEUE;
            case "REQUEST_CONVERTER" -> REQUEST_CONVERTER_QUEUE;
            case "API_CONNECTOR" -> API_CONNECTORS_QUEUE;
            default -> null;
        };
    }
}
