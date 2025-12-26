package com.anthem.pagw.core.contract;

import java.util.List;
import java.util.Map;

/**
 * Service flow definitions showing routing between services.
 * 
 * PIPELINE FLOW:
 * 
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                           PAGW SERVICE PIPELINE                              │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * 
 *                              ┌──────────────┐
 *                              │  API Gateway │
 *                              │  POST /submit│
 *                              └──────┬───────┘
 *                                     │
 *                                     ▼
 *                         ┌───────────────────────┐
 *                         │    ORCHESTRATOR       │
 *                         │ - Generate PAGW ID    │
 *                         │ - Check idempotency   │
 *                         │ - Write to outbox     │
 *                         └───────────┬───────────┘
 *                                     │
 *                    ┌────────────────┼────────────────┐
 *                    │ SYNC PATH      │ ASYNC PATH     │
 *                    │ (15s timeout)  │ (queued)       │
 *                    ▼                ▼                │
 *         ┌──────────────────┐  ┌─────────────────┐   │
 *         │ REQUEST_PARSER   │  │ REQUEST_PARSER  │◄──┘
 *         │ - Parse FHIR     │  │ (from SQS)      │
 *         └────────┬─────────┘  └────────┬────────┘
 *                  │                     │
 *      ┌───────────┴─────────────────────┴───────────┐
 *      │ OnSuccess                    OnFailure      │
 *      ▼                                   │         │
 * ┌──────────────────┐                     │         │
 * │ BUSINESS_VALIDATOR│                    │         │
 * │ - NPI validation  │                    │         │
 * │ - Date validation │                    │         │
 * │ - Business rules  │                    │         │
 * └────────┬─────────┘                     │         │
 *          │                               │         │
 *          ├──────────────────────────────►├─────────┤
 *          │ OnSuccess                OnFailure      │
 *          ▼                               │         │
 * ┌────────────────────┐                   │         │
 * │ hasAttachments?    │                   │         │
 * └────────┬───────────┘                   │         │
 *          │                               │         │
 *    ┌─────┴─────┐                         │         │
 *    │YES        │NO                       │         │
 *    ▼           │                         │         │
 * ┌──────────────────┐                     │         │
 * │ATTACHMENT_HANDLER│                     │         │
 * │ - Download files │                     │         │
 * │ - Virus scan     │                     │         │
 * │ - Store to S3    │                     │         │
 * └────────┬─────────┘                     │         │
 *          │                               │         │
 *          ├──────────────────────────────►├─────────┤
 *          │ OnSuccess                OnFailure      │
 *          ▼                               │         │
 * ┌──────────────────┐◄────────────────────┘         │
 * │ REQUEST_ENRICHER │                               │
 * │ - Member lookup  │                               │
 * │ - Provider lookup│                               │
 * │ - Benefit check  │                               │
 * └────────┬─────────┘                               │
 *          │                                         │
 *          ├────────────────────────────────────────►├
 *          │ OnSuccess                     OnFailure │
 *          ▼                                         │
 * ┌──────────────────┐                               │
 * │ CANONICAL_MAPPER │                               │
 * │ - FHIR → X12 278 │                               │
 * │ - Add envelope   │                               │
 * └────────┬─────────┘                               │
 *          │                                         │
 *          ├────────────────────────────────────────►├
 *          │ OnSuccess                     OnFailure │
 *          ▼                                         │
 * ┌──────────────────┐                               │
 * │  API_CONNECTOR   │                               │
 * │ - Auth with payer│                               │
 * │ - Submit X12     │                               │
 * │ - Get response   │                               │
 * └────────┬─────────┘                               │
 *          │                                         │
 *          │ OnSuccess (A1,A2,A3,A4)                 │
 *          │ OnFailure ─────────────────────────────►├
 *          ▼                                         │
 * ┌──────────────────┐◄──────────────────────────────┘
 * │ RESPONSE_BUILDER │
 * │ - X12 → FHIR     │
 * │ - ClaimResponse  │
 * │ - or OperationOut│
 * └────────┬─────────┘
 *          │
 *          ▼
 * ┌──────────────────┐
 * │ CALLBACK_HANDLER │
 * │ - Webhook notify │
 * │ - Retry logic    │
 * │ - Audit log      │
 * └────────┬─────────┘
 *          │
 *          ▼
 *       [END]
 */
public final class ServiceFlow {

    private ServiceFlow() {}

    /**
     * Service identifiers
     */
    public static final String ORCHESTRATOR = "ORCHESTRATOR";
    public static final String REQUEST_PARSER = "REQUEST_PARSER";
    public static final String BUSINESS_VALIDATOR = "BUSINESS_VALIDATOR";
    public static final String ATTACHMENT_HANDLER = "ATTACHMENT_HANDLER";
    public static final String REQUEST_ENRICHER = "REQUEST_ENRICHER";
    public static final String CANONICAL_MAPPER = "CANONICAL_MAPPER";
    public static final String API_CONNECTOR = "API_CONNECTOR";
    public static final String RESPONSE_BUILDER = "RESPONSE_BUILDER";
    public static final String CALLBACK_HANDLER = "CALLBACK_HANDLER";
    public static final String DLQ = "DLQ";
    public static final String NONE = "NONE";

    /**
     * Flow routing definitions
     */
    public static final Map<String, ServiceRoute> ROUTES = Map.of(
            ORCHESTRATOR, new ServiceRoute(
                    REQUEST_PARSER,                    // onSuccess (async)
                    NONE,                              // onSuccess (sync - immediate response)
                    NONE                               // onFailure - return error
            ),
            REQUEST_PARSER, new ServiceRoute(
                    BUSINESS_VALIDATOR,                // onSuccess
                    CALLBACK_HANDLER,                  // onFailure
                    CALLBACK_HANDLER                   // onFailure (via RESPONSE_BUILDER)
            ),
            BUSINESS_VALIDATOR, new ServiceRoute(
                    ATTACHMENT_HANDLER,                // onSuccess (if hasAttachments)
                    REQUEST_ENRICHER,                  // onSuccess (if no attachments)
                    RESPONSE_BUILDER                   // onFailure
            ),
            ATTACHMENT_HANDLER, new ServiceRoute(
                    REQUEST_ENRICHER,                  // onSuccess
                    RESPONSE_BUILDER,                  // onFailure
                    RESPONSE_BUILDER                   // onFailure
            ),
            REQUEST_ENRICHER, new ServiceRoute(
                    CANONICAL_MAPPER,                  // onSuccess
                    RESPONSE_BUILDER,                  // onFailure (not found)
                    DLQ                                // onFailure (service down - retry)
            ),
            CANONICAL_MAPPER, new ServiceRoute(
                    API_CONNECTOR,                     // onSuccess
                    RESPONSE_BUILDER,                  // onFailure
                    RESPONSE_BUILDER                   // onFailure
            ),
            API_CONNECTOR, new ServiceRoute(
                    RESPONSE_BUILDER,                  // onSuccess
                    RESPONSE_BUILDER,                  // onFailure (payer error)
                    DLQ                                // onFailure (timeout - retry)
            ),
            RESPONSE_BUILDER, new ServiceRoute(
                    CALLBACK_HANDLER,                  // onSuccess
                    CALLBACK_HANDLER,                  // onFailure (with error status)
                    CALLBACK_HANDLER                   // onFailure
            ),
            CALLBACK_HANDLER, new ServiceRoute(
                    NONE,                              // onSuccess - END
                    NONE,                              // onFailure (exhausted retries)
                    CALLBACK_HANDLER                   // onFailure (retry)
            )
    );

    /**
     * Service route definition
     */
    public static class ServiceRoute {
        public final String onSuccess;
        public final String onSuccessAlternate;  // For conditional routing
        public final String onFailure;

        public ServiceRoute(String onSuccess, String onSuccessAlternate, String onFailure) {
            this.onSuccess = onSuccess;
            this.onSuccessAlternate = onSuccessAlternate;
            this.onFailure = onFailure;
        }
    }

    /**
     * Status values used throughout the pipeline
     */
    public static final class Status {
        // Request lifecycle statuses
        public static final String RECEIVED = "RECEIVED";
        public static final String PARSING = "PARSING";
        public static final String PARSED = "PARSED";
        public static final String VALIDATING = "VALIDATING";
        public static final String VALIDATED = "VALIDATED";
        public static final String PROCESSING_ATTACHMENTS = "PROCESSING_ATTACHMENTS";
        public static final String ATTACHMENTS_PROCESSED = "ATTACHMENTS_PROCESSED";
        public static final String ENRICHING = "ENRICHING";
        public static final String ENRICHED = "ENRICHED";
        public static final String MAPPING = "MAPPING";
        public static final String MAPPED = "MAPPED";
        public static final String SUBMITTING = "SUBMITTING";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String BUILDING_RESPONSE = "BUILDING_RESPONSE";
        public static final String RESPONSE_BUILT = "RESPONSE_BUILT";
        public static final String NOTIFYING = "NOTIFYING";
        public static final String COMPLETED = "COMPLETED";
        
        // Error statuses
        public static final String PARSE_FAILED = "PARSE_FAILED";
        public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
        public static final String ATTACHMENT_FAILED = "ATTACHMENT_FAILED";
        public static final String ENRICHMENT_FAILED = "ENRICHMENT_FAILED";
        public static final String MAPPING_FAILED = "MAPPING_FAILED";
        public static final String SUBMISSION_FAILED = "SUBMISSION_FAILED";
        public static final String CALLBACK_FAILED = "CALLBACK_FAILED";
        public static final String ERROR = "ERROR";
        
        // Decision statuses (from payer)
        public static final String APPROVED = "APPROVED";
        public static final String DENIED = "DENIED";
        public static final String PENDED = "PENDED";
        public static final String MODIFIED = "MODIFIED";
        public static final String CANCELLED = "CANCELLED";
        
        private Status() {}
    }

    /**
     * Queue names for each service
     */
    public static final class Queues {
        public static final String ORCHESTRATOR_QUEUE = "pagw-orchestrator-queue";
        public static final String REQUEST_PARSER_QUEUE = "pagw-request-parser-queue";
        public static final String BUSINESS_VALIDATOR_QUEUE = "pagw-business-validator-queue";
        public static final String ATTACHMENT_HANDLER_QUEUE = "pagw-attachment-handler-queue";
        public static final String REQUEST_ENRICHER_QUEUE = "pagw-request-enricher-queue";
        public static final String CANONICAL_MAPPER_QUEUE = "pagw-canonical-mapper-queue";
        public static final String API_CONNECTOR_QUEUE = "pagw-api-connector-queue";
        public static final String RESPONSE_BUILDER_QUEUE = "pagw-response-builder-queue";
        public static final String CALLBACK_HANDLER_QUEUE = "pagw-callback-handler-queue";
        public static final String DLQ = "pagw-dlq";
        
        private Queues() {}
    }
}
