package com.anthem.pagw.core.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Service outcome contracts defining success/failure behaviors for each PAGW microservice.
 * 
 * Each service has clearly defined:
 * - Input contract (what it expects)
 * - Success outcome (what it produces on success)
 * - Failure outcome (what it produces on failure)
 * - Next service routing
 */
public final class ServiceContracts {

    private ServiceContracts() {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // 1. ORCHESTRATOR SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Orchestrator - Entry point for PA requests.
     * 
     * Responsibilities:
     * - Generate PAGW ID
     * - Validate idempotency
     * - Write to outbox table
     * - Return immediate acknowledgment
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrchestratorInput {
        private String fhirBundle;          // FHIR Claim bundle
        private String idempotencyKey;      // Client-provided dedup key
        private String tenant;              // Provider tenant
        private String authenticatedProviderId;
        private boolean syncProcessing;     // Default true for 15s response
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrchestratorSuccessOutput {
        private String pagwId;              // Generated tracking ID
        private String status;              // QUEUED, APPROVED, DENIED, PENDED
        private String disposition;         // ClaimResponse disposition
        private String claimResponseBundle; // Full FHIR response (sync only)
        private Long processingTimeMs;
        
        // OnSuccess Actions:
        // - If sync approved/denied: Return ClaimResponse immediately
        // - If sync pended: Return ClaimResponse with pended status
        // - If async: Queue to REQUEST_PARSER
        
        public static final String NEXT_SERVICE_SYNC = "NONE";  // Sync response returned
        public static final String NEXT_SERVICE_ASYNC = "REQUEST_PARSER";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrchestratorFailureOutput {
        private String errorCode;           // VALIDATION_ERROR, DUPLICATE_REQUEST, AUTH_ERROR
        private String errorMessage;
        private List<String> validationErrors;
        private String operationOutcomeBundle;  // FHIR OperationOutcome
        
        // OnFailure Actions:
        // - Return OperationOutcome with HTTP 400/401/409
        // - Log to audit trail
        // - DO NOT queue to downstream services
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 2. REQUEST PARSER SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Request Parser - Parse and validate FHIR structure.
     * 
     * Responsibilities:
     * - Parse FHIR Claim bundle
     * - Extract key identifiers (patient, provider, service)
     * - Validate FHIR schema compliance
     * - Store parsed data to S3
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestParserInput {
        private String pagwId;
        private String payloadBucket;
        private String payloadKey;          // S3 path to raw FHIR bundle
        private String tenant;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestParserSuccessOutput {
        private String pagwId;
        private String parsedDataKey;       // S3 path to parsed/normalized data
        
        // Extracted identifiers
        private String patientId;
        private String patientMemberId;
        private String providerNpi;
        private String providerTaxId;
        private String facilityNpi;
        
        // Service details
        private String serviceTypeCode;
        private String placeOfServiceCode;
        private List<String> cptCodes;
        private List<String> icd10Codes;
        private String primaryDiagnosis;
        
        // Dates
        private String serviceStartDate;
        private String serviceEndDate;
        
        // Flags
        private boolean hasAttachments;
        private int attachmentCount;
        private boolean isUrgent;
        
        // OnSuccess Actions:
        // - Store parsed data to S3
        // - Update request_tracker status = PARSED
        // - Queue to BUSINESS_VALIDATOR
        
        public static final String NEXT_SERVICE = "BUSINESS_VALIDATOR";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestParserFailureOutput {
        private String pagwId;
        private String errorCode;           // INVALID_FHIR, MISSING_REQUIRED_FIELD, SCHEMA_VIOLATION
        private String errorMessage;
        private List<ParseError> parseErrors;
        
        @Data
        @Builder
        public static class ParseError {
            private String path;            // FHIR path (e.g., Claim.provider.identifier)
            private String code;
            private String message;
        }
        
        // OnFailure Actions:
        // - Update request_tracker status = PARSE_FAILED
        // - Queue to CALLBACK_HANDLER with VALIDATION_FAILED status
        // - DO NOT continue to validator
        
        public static final String NEXT_SERVICE = "CALLBACK_HANDLER";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 3. BUSINESS VALIDATOR SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Business Validator - Apply business rules.
     * 
     * Responsibilities:
     * - Validate NPI format (Luhn)
     * - Validate date ranges
     * - Apply payer-specific rules
     * - Check service eligibility
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessValidatorInput {
        private String pagwId;
        private String parsedDataKey;       // S3 path to parsed data
        private String tenant;
        private String payerId;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessValidatorSuccessOutput {
        private String pagwId;
        private boolean valid;
        private List<ValidationWarning> warnings;  // Non-blocking issues
        private String validatedDataKey;    // S3 path to validated data
        
        @Data
        @Builder
        public static class ValidationWarning {
            private String code;
            private String message;
            private String field;
        }
        
        // OnSuccess Actions:
        // - Update request_tracker status = VALIDATED
        // - If hasAttachments: Queue to ATTACHMENT_HANDLER
        // - Else: Queue to REQUEST_ENRICHER
        
        public static final String NEXT_SERVICE_WITH_ATTACHMENTS = "ATTACHMENT_HANDLER";
        public static final String NEXT_SERVICE_NO_ATTACHMENTS = "REQUEST_ENRICHER";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessValidatorFailureOutput {
        private String pagwId;
        private String errorCode;           // INVALID_NPI, INVALID_DATE_RANGE, RULE_VIOLATION
        private List<ValidationError> errors;
        
        @Data
        @Builder
        public static class ValidationError {
            private String code;
            private String message;
            private String field;
            private String ruleId;
        }
        
        // OnFailure Actions:
        // - Update request_tracker status = VALIDATION_FAILED
        // - Queue to RESPONSE_BUILDER to create OperationOutcome
        // - Then queue to CALLBACK_HANDLER
        
        public static final String NEXT_SERVICE = "RESPONSE_BUILDER";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 4. ATTACHMENT HANDLER SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Attachment Handler - Process clinical attachments.
     * 
     * Responsibilities:
     * - Download attachments from S3/URLs
     * - Validate file types and sizes
     * - Virus scan
     * - Convert to payer-required format
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentHandlerInput {
        private String pagwId;
        private String payloadKey;
        private List<AttachmentReference> attachments;
        
        @Data
        @Builder
        public static class AttachmentReference {
            private String id;
            private String contentType;
            private String url;             // S3 or external URL
            private String title;
        }
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentHandlerSuccessOutput {
        private String pagwId;
        private List<ProcessedAttachment> processedAttachments;
        private String attachmentMetadataKey;  // S3 path to attachment manifest
        
        @Data
        @Builder
        public static class ProcessedAttachment {
            private String id;
            private String s3Bucket;
            private String s3Key;
            private String contentType;
            private Long sizeBytes;
            private String checksum;
            private boolean virusScanPassed;
        }
        
        // OnSuccess Actions:
        // - Store processed attachments to S3
        // - Update request_tracker with attachment metadata
        // - Queue to REQUEST_ENRICHER
        
        public static final String NEXT_SERVICE = "REQUEST_ENRICHER";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentHandlerFailureOutput {
        private String pagwId;
        private String errorCode;           // VIRUS_DETECTED, INVALID_FORMAT, SIZE_EXCEEDED, DOWNLOAD_FAILED
        private String errorMessage;
        private String failedAttachmentId;
        
        // OnFailure Actions:
        // - Update request_tracker status = ATTACHMENT_FAILED
        // - Queue to RESPONSE_BUILDER (error response)
        // - Then queue to CALLBACK_HANDLER
        
        public static final String NEXT_SERVICE = "RESPONSE_BUILDER";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 5. REQUEST ENRICHER SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Request Enricher - Add member/provider data from external sources.
     * 
     * Responsibilities:
     * - Fetch member eligibility from eligibility service
     * - Fetch provider demographics from provider directory
     * - Add plan/benefit information
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestEnricherInput {
        private String pagwId;
        private String validatedDataKey;
        private String memberId;
        private String providerNpi;
        private String planId;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestEnricherSuccessOutput {
        private String pagwId;
        private String enrichedDataKey;     // S3 path to enriched data
        
        // Enrichment results
        private MemberInfo memberInfo;
        private ProviderInfo providerInfo;
        private BenefitInfo benefitInfo;
        
        @Data
        @Builder
        public static class MemberInfo {
            private String subscriberId;
            private String dependentSequence;
            private String coverageEffectiveDate;
            private String coverageTermDate;
            private boolean isActive;
        }
        
        @Data
        @Builder
        public static class ProviderInfo {
            private String providerName;
            private String specialty;
            private boolean isInNetwork;
            private boolean isGoldCard;
            private Double approvalRate;
        }
        
        @Data
        @Builder
        public static class BenefitInfo {
            private String planName;
            private String lineOfBusiness;
            private boolean priorAuthRequired;
            private String delegatedPayerId;
        }
        
        // OnSuccess Actions:
        // - Store enriched data to S3
        // - Update request_tracker status = ENRICHED
        // - Queue to CANONICAL_MAPPER
        
        public static final String NEXT_SERVICE = "CANONICAL_MAPPER";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestEnricherFailureOutput {
        private String pagwId;
        private String errorCode;           // MEMBER_NOT_FOUND, PROVIDER_NOT_FOUND, ELIGIBILITY_SERVICE_DOWN
        private String errorMessage;
        private boolean isRetryable;
        
        // OnFailure Actions:
        // - If retryable: Retry with backoff (max 3 attempts)
        // - If member/provider not found: Queue to RESPONSE_BUILDER (error)
        // - If service down: Queue to DLQ for retry
        
        public static final String NEXT_SERVICE_ERROR = "RESPONSE_BUILDER";
        public static final String NEXT_SERVICE_RETRY = "DLQ";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 6. CANONICAL MAPPER SERVICE (REQUEST CONVERTER)
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Canonical Mapper - Convert FHIR to X12 278.
     * 
     * Responsibilities:
     * - Map FHIR Claim to X12 278 request
     * - Apply payer-specific mapping rules
     * - Generate X12 envelope
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CanonicalMapperInput {
        private String pagwId;
        private String enrichedDataKey;
        private String payerId;
        private String x12Version;          // 005010X217 or 005010X278
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CanonicalMapperSuccessOutput {
        private String pagwId;
        private String x12RequestKey;       // S3 path to X12 278 request
        private String x12Version;
        private String interchangeControlNumber;
        private String transactionSetControlNumber;
        
        // OnSuccess Actions:
        // - Store X12 to S3
        // - Update request_tracker status = MAPPED
        // - Queue to API_CONNECTOR
        
        public static final String NEXT_SERVICE = "API_CONNECTOR";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CanonicalMapperFailureOutput {
        private String pagwId;
        private String errorCode;           // MAPPING_FAILED, UNSUPPORTED_SERVICE_TYPE, MISSING_REQUIRED_SEGMENT
        private String errorMessage;
        private List<MappingError> mappingErrors;
        
        @Data
        @Builder
        public static class MappingError {
            private String sourceField;
            private String targetSegment;
            private String message;
        }
        
        // OnFailure Actions:
        // - Update request_tracker status = MAPPING_FAILED
        // - Queue to RESPONSE_BUILDER (error response)
        
        public static final String NEXT_SERVICE = "RESPONSE_BUILDER";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 7. API CONNECTOR SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS API Connector - Submit to payer APIs.
     * 
     * Responsibilities:
     * - Authenticate with payer (OAuth2, mTLS, API Key)
     * - Submit X12 278 request
     * - Handle sync/async responses
     * - Parse X12 278 response
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiConnectorInput {
        private String pagwId;
        private String x12RequestKey;
        private String payerId;
        private String apiEndpoint;
        private String authMethod;
        private String credentialsSecretArn;
        private Map<String, String> headers;
        private Integer timeoutSeconds;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiConnectorSuccessOutput {
        private String pagwId;
        private String decision;            // A1=Certified, A2=NotCertified, A3=Pended, A4=Modified
        private String payerTrackingNumber;
        private String x12ResponseKey;      // S3 path to X12 278 response
        private String certificationNumber;
        private String effectiveDate;
        private String expirationDate;
        private Integer authorizedUnits;
        private List<String> modificationReasons;
        
        // Decision codes
        public static final String DECISION_CERTIFIED = "A1";
        public static final String DECISION_NOT_CERTIFIED = "A2";
        public static final String DECISION_PENDED = "A3";
        public static final String DECISION_MODIFIED = "A4";
        
        // OnSuccess Actions:
        // - Store X12 response to S3
        // - Update request_tracker status based on decision
        // - Queue to RESPONSE_BUILDER
        
        public static final String NEXT_SERVICE = "RESPONSE_BUILDER";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiConnectorFailureOutput {
        private String pagwId;
        private String errorCode;           // AUTH_FAILED, TIMEOUT, PAYER_ERROR, INVALID_RESPONSE
        private String errorMessage;
        private Integer httpStatus;
        private String payerErrorCode;
        private String payerErrorMessage;
        private boolean isRetryable;
        
        // OnFailure Actions:
        // - If retryable (timeout, 5xx): Retry with backoff
        // - If auth failed: Alert and queue to DLQ
        // - If payer error: Queue to RESPONSE_BUILDER with error
        
        public static final String NEXT_SERVICE_ERROR = "RESPONSE_BUILDER";
        public static final String NEXT_SERVICE_RETRY = "DLQ";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 8. RESPONSE BUILDER SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Response Builder - Build FHIR ClaimResponse.
     * 
     * Responsibilities:
     * - Map X12 278 response to FHIR ClaimResponse
     * - Build OperationOutcome for errors
     * - Include authorization details
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseBuilderInput {
        private String pagwId;
        private String status;              // APPROVED, DENIED, PENDED, ERROR
        private String x12ResponseKey;      // May be null for errors
        private String decision;
        private String payerTrackingNumber;
        private List<String> errorCodes;
        private List<String> errorMessages;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseBuilderSuccessOutput {
        private String pagwId;
        private String fhirResponseKey;     // S3 path to FHIR ClaimResponse
        private String resourceType;        // ClaimResponse or OperationOutcome
        private String disposition;
        private String preAuthRef;
        private String preAuthPeriodStart;
        private String preAuthPeriodEnd;
        
        // OnSuccess Actions:
        // - Store FHIR response to S3
        // - Update request_tracker with response info
        // - Queue to CALLBACK_HANDLER
        
        public static final String NEXT_SERVICE = "CALLBACK_HANDLER";
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseBuilderFailureOutput {
        private String pagwId;
        private String errorCode;           // MAPPING_FAILED, INVALID_X12_RESPONSE
        private String errorMessage;
        
        // OnFailure Actions:
        // - Build generic error OperationOutcome
        // - Queue to CALLBACK_HANDLER anyway (with error status)
        
        public static final String NEXT_SERVICE = "CALLBACK_HANDLER";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 9. CALLBACK HANDLER SERVICE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * PAS Callback Handler - Notify requesting provider.
     * 
     * Responsibilities:
     * - Send webhook to provider callback URL
     * - Support polling status updates
     * - Handle delivery retries
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallbackHandlerInput {
        private String pagwId;
        private String status;              // Final status
        private String fhirResponseKey;
        private String callbackUrl;         // Provider webhook URL
        private String subscriptionId;      // If using FHIR subscriptions
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallbackHandlerSuccessOutput {
        private String pagwId;
        private boolean delivered;
        private String deliveryMethod;      // WEBHOOK, POLLING, SUBSCRIPTION
        private String deliveredAt;
        private Integer httpStatus;
        
        // OnSuccess Actions:
        // - Update request_tracker status = COMPLETED
        // - Update request_tracker.callbackSentAt
        // - Log audit entry
        // - END OF PIPELINE
        
        public static final String NEXT_SERVICE = "NONE"; // Terminal
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallbackHandlerFailureOutput {
        private String pagwId;
        private String errorCode;           // WEBHOOK_FAILED, TIMEOUT, INVALID_URL
        private String errorMessage;
        private Integer attemptNumber;
        private Integer maxAttempts;
        private boolean willRetry;
        
        // OnFailure Actions:
        // - If attempts < maxAttempts: Schedule retry with exponential backoff
        // - If exhausted: Mark callback_status = DELIVERY_FAILED
        // - Always mark request as COMPLETED (response is stored for polling)
        
        public static final String NEXT_SERVICE = "RETRY_QUEUE"; // Or NONE if exhausted
    }
}
