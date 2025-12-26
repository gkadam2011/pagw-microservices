package com.anthem.pagw.core.service;

/**
 * Example usage of EventTrackerService in PAGW microservices.
 * 
 * This demonstrates how to integrate event tracking into your SQS listeners
 * for comprehensive observability.
 */
public class EventTrackerServiceUsageExample {
    
    private final EventTrackerService eventTrackerService;
    
    public EventTrackerServiceUsageExample(EventTrackerService eventTrackerService) {
        this.eventTrackerService = eventTrackerService;
    }
    
    /**
     * Example: Parser service processing a request
     */
    public void parseRequest(String pagwId, String tenant, String s3Key) {
        long startTime = System.currentTimeMillis();
        
        // Log stage start
        eventTrackerService.logStageStart(
            pagwId, 
            tenant, 
            "PARSING", 
            "PARSE_START",
            String.format("{\"s3Key\":\"%s\"}", s3Key)
        );
        
        try {
            // ... do parsing work ...
            
            // Log success
            long duration = System.currentTimeMillis() - startTime;
            eventTrackerService.logStageComplete(
                pagwId, 
                tenant, 
                "PARSING", 
                "PARSE_OK", 
                duration,
                String.format("{\"bundleType\":\"PAS\",\"resourceCount\":10}")
            );
            
        } catch (Exception e) {
            // Log failure
            eventTrackerService.logStageError(
                pagwId, 
                tenant, 
                "PARSING", 
                "PARSE_FAIL",
                "PARSE_ERROR",
                e.getMessage(),
                false,  // Not retryable (validation error)
                null
            );
            throw e;
        }
    }
    
    /**
     * Example: API Connector with retryable errors
     */
    public void submitToDownstream(String pagwId, String tenant, String payerId) {
        long startTime = System.currentTimeMillis();
        
        eventTrackerService.logStageStart(
            pagwId, 
            tenant, 
            "SUBMISSION", 
            "SUBMIT_START",
            String.format("{\"payerId\":\"%s\"}", payerId)
        );
        
        try {
            // ... submit to downstream ...
            
            long duration = System.currentTimeMillis() - startTime;
            eventTrackerService.logStageComplete(
                pagwId, 
                tenant, 
                "SUBMISSION", 
                "SUBMIT_OK", 
                duration,
                String.format("{\"externalId\":\"EXT-123\"}")
            );
            
        } catch (Exception e) {
            // Check if it's a timeout (retryable)
            if (e.getClass().getSimpleName().contains("Timeout")) {
            // Retryable error - log with retry info
            java.time.Instant nextRetry = java.time.Instant.now()
                .plus(5, java.time.temporal.ChronoUnit.MINUTES);
            
            eventTrackerService.logStageError(
                pagwId, 
                tenant, 
                "SUBMISSION", 
                "DOWNSTREAM_TIMEOUT",
                "TIMEOUT",
                e.getMessage(),
                true,  // Retryable
                nextRetry
            );
            
            // Send to DLQ or retry queue
            
        } else {
            // Non-retryable error (e.g., validation)
            eventTrackerService.logStageError(
                pagwId, 
                tenant, 
                "SUBMISSION", 
                "SUBMIT_FAIL",
                "VALIDATION_ERROR",
                e.getMessage(),
                false,  // Not retryable
                null
            );
            throw e;
            }
        }
    }
    
    /**
     * Example: SQS Listener pattern (apply to all 10 services)
     * 
     * Note: @SqsListener annotation from Spring Cloud AWS
     * PagwMessage from com.anthem.pagw.core.model.PagwMessage
     */
    // @SqsListener(queueName = "PAS_REQUEST_PARSER")
    public void onParseRequest(Object message) {
        // Cast to PagwMessage in actual implementation
        // PagwMessage msg = (PagwMessage) message;
        String pagwId = "PAGW-EXAMPLE"; // msg.getPagwId();
        String tenant = "elevance";     // msg.getTenant();
        
        // Log event start
        long seqNo = eventTrackerService.logStageStart(
            pagwId, 
            tenant, 
            "PARSING", 
            "PARSE_START"
        );
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Process message
            // parseBundle(message);
            
            // Log success
            long duration = System.currentTimeMillis() - startTime;
            eventTrackerService.logStageComplete(
                pagwId, 
                tenant, 
                "PARSING", 
                "PARSE_OK", 
                duration
            );
            
        } catch (Exception e) {
            // Log failure
            eventTrackerService.logStageError(
                pagwId, 
                tenant, 
                "PARSING", 
                "PARSE_FAIL",
                "PARSE_ERROR",
                e.getMessage()
            );
            
            // Re-throw or send to DLQ
            throw new RuntimeException("Parse failed", e);
        }
    }
    
    /**
     * Example: Query timeline for debugging
     */
    public void debugRequest(String pagwId) {
        var timeline = eventTrackerService.getTimeline(pagwId);
        
        System.out.println("Request Timeline:");
        for (var event : timeline) {
            System.out.printf("[%d] %s %s - %s (%dms)%n",
                event.getSequenceNo(),
                event.getStage(),
                event.getEventType(),
                event.getStatus(),
                event.getDurationMs() != null ? event.getDurationMs() : 0
            );
            
            if (event.isFailure()) {
                System.out.printf("    ERROR: %s - %s%n", 
                    event.getErrorCode(), 
                    event.getErrorMessage()
                );
            }
        }
    }
    
    /**
     * Example: Retry failed events
     */
    public void retryFailedEvents(String tenant) {
        var failedEvents = eventTrackerService.getFailedRetryableEvents(tenant, 60);
        
        for (var event : failedEvents) {
            if (event.canRetry()) {
                System.out.printf("Retrying: pagwId=%s, stage=%s, attempt=%d%n",
                    event.getPagwId(),
                    event.getStage(),
                    event.getAttempt()
                );
                
                // Log retry attempt
                eventTrackerService.logRetryAttempt(
                    event.getPagwId(),
                    tenant,
                    event.getStage(),
                    event.getEventType(),
                    event.getAttempt() + 1
                );
                
                // Re-queue message for retry
                // ...
            }
        }
    }
}
