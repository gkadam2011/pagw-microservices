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
 */
@Component
public class OrchestratorResponseListener {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorResponseListener.class);

    private final OutboxService outboxService;
    private final RequestTrackerService trackerService;
    private final String requestParserQueue;
    private final String businessValidatorQueue;
    private final String requestEnricherQueue;
    private final String requestConverterQueue;
    private final String apiConnectorsQueue;

    public OrchestratorResponseListener(
            OutboxService outboxService,
            RequestTrackerService trackerService,
            @Value("${pagw.aws.sqs.request-parser-queue}") String requestParserQueue,
            @Value("${pagw.aws.sqs.business-validator-queue}") String businessValidatorQueue,
            @Value("${pagw.aws.sqs.request-enricher-queue}") String requestEnricherQueue,
            @Value("${pagw.aws.sqs.request-converter-queue}") String requestConverterQueue,
            @Value("${pagw.aws.sqs.api-connectors-queue}") String apiConnectorsQueue) {
        this.outboxService = outboxService;
        this.trackerService = trackerService;
        this.requestParserQueue = requestParserQueue;
        this.businessValidatorQueue = businessValidatorQueue;
        this.requestEnricherQueue = requestEnricherQueue;
        this.requestConverterQueue = requestConverterQueue;
        this.apiConnectorsQueue = apiConnectorsQueue;
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
     */
    private String determineTargetQueue(String stage) {
        return switch (stage) {
            case "REQUEST_PARSER" -> requestParserQueue;
            case "BUSINESS_VALIDATOR" -> businessValidatorQueue;
            case "REQUEST_ENRICHER" -> requestEnricherQueue;
            case "REQUEST_CONVERTER" -> requestConverterQueue;
            case "API_CONNECTOR" -> apiConnectorsQueue;
            default -> null;
        };
    }
}
