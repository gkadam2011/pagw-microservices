package com.anthem.pagw.orchestrator.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.model.RequestTracker;
import com.anthem.pagw.core.service.*;
import com.anthem.pagw.core.util.PagwIdGenerator;
import com.anthem.pagw.orchestrator.model.PasRequest;
import com.anthem.pagw.orchestrator.model.PasResponse;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrator Service - handles incoming PAS requests.
 * 
 * Supports two processing modes per Da Vinci PAS IG:
 * 
 * 1. SYNCHRONOUS (Default) - For 15-second response requirement:
 *    Orchestrator → RequestParser → BusinessValidator → [Decision] → Response
 *    If decision can be made immediately, return ClaimResponse.
 *    If not, return "pended" and queue for async.
 * 
 * 2. ASYNCHRONOUS - For complex cases requiring manual review:
 *    Queue to SQS for full pipeline processing with callback notification.
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/specification.html">Da Vinci PAS IG</a>
 */
@Service
public class OrchestratorService {
    
    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    
    private final S3Service s3Service;
    private final RequestTrackerService requestTrackerService;
    private final OutboxService outboxService;
    @Nullable
    private final PhiEncryptionService phiEncryptionService;
    private final AuditService auditService;
    @Nullable
    private final IdempotencyService idempotencyService;
    private final SyncProcessingService syncProcessingService;
    private final PagwProperties properties;
    
    public OrchestratorService(
            S3Service s3Service,
            RequestTrackerService requestTrackerService,
            OutboxService outboxService,
            @Nullable PhiEncryptionService phiEncryptionService,
            AuditService auditService,
            @Nullable IdempotencyService idempotencyService,
            SyncProcessingService syncProcessingService,
            PagwProperties properties) {
        this.s3Service = s3Service;
        this.requestTrackerService = requestTrackerService;
        this.outboxService = outboxService;
        this.phiEncryptionService = phiEncryptionService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.syncProcessingService = syncProcessingService;
        this.properties = properties;
    }
    
    /**
     * Process incoming PAS request.
     * 
     * Da Vinci PAS Compliance:
     * - Default: Synchronous processing with 15-second response requirement
     * - Fallback: Async queue processing if sync cannot complete in time
     * 
     * Processing modes:
     * 1. Sync path: Orchestrator → Parser → Validator → [Decision] → Immediate Response
     * 2. Async path: Queue to SQS → Full pipeline → Callback notification
     */
    @Transactional
    public PasResponse processRequest(PasRequest request) {
        // Generate unique PAGW ID
        String pagwId = PagwIdGenerator.generate();
        String messageId = UUID.randomUUID().toString();
        Instant receivedAt = Instant.now();
        
        log.info("Processing request: pagwId={}, type={}, tenant={}, syncMode={}", 
                pagwId, request.getRequestType(), request.getTenant(), request.isSyncProcessing());
        
        // Check idempotency (Da Vinci PAS requires unique Bundle.identifier)
        String idempotencyKey = request.getIdempotencyKey() != null ? 
                request.getIdempotencyKey() : pagwId;
        
        if (idempotencyService != null && !idempotencyService.checkAndSet(idempotencyKey)) {
            log.warn("Duplicate request detected: idempotencyKey={}", idempotencyKey);
            return buildDuplicateResponse(idempotencyKey);
        }
        
        try {
            // Encrypt PHI fields if enabled and encryption service is available
            String processedBundle = request.getFhirBundle();
            if (phiEncryptionService != null && properties.getEncryption().isEncryptPhiFields()) {
                Map<String, String> encryptionContext = Map.of(
                        "pagwId", pagwId,
                        "tenant", request.getTenant() != null ? request.getTenant() : "default"
                );
                processedBundle = phiEncryptionService.encryptPhiFields(
                        request.getFhirBundle(),
                        properties.getEncryption().getPhiFields(),
                        encryptionContext
                );
            } else if (properties.getEncryption().isEncryptPhiFields()) {
                log.warn("PHI encryption enabled but service not available - storing unencrypted (local mode)");
            }
            
            // Store raw request in S3 using standardized path: {YYYYMM}/{pagwId}/request/raw.json
            String requestBucket = properties.getAws().getS3().getRequestBucket();
            String rawKey = PagwProperties.S3Paths.raw(pagwId);
            s3Service.putObject(requestBucket, rawKey, processedBundle);
            
            log.debug("Stored raw request: bucket={}, key={}", requestBucket, rawKey);
            
            // Create request tracker (in same transaction)
            RequestTracker tracker = RequestTracker.builder()
                    .pagwId(pagwId)
                    .status(RequestTracker.STATUS_RECEIVED)
                    .tenant(request.getTenant())
                    .sourceSystem("APIGEE")
                    .requestType(request.getRequestType())
                    .lastStage("ORCHESTRATOR")
                    .nextStage(RequestTracker.STAGE_REQUEST_PARSER)
                    .rawS3Bucket(requestBucket)
                    .rawS3Key(rawKey)
                    .containsPhi(true)
                    .idempotencyKey(idempotencyKey)
                    .receivedAt(receivedAt)
                    .build();
            
            requestTrackerService.create(tracker);
            
            // Log audit event
            auditService.logRequestCreated(pagwId, "pasorchestrator", request.getCorrelationId());
            
            // Attempt synchronous processing first (Da Vinci PAS default)
            if (request.isSyncProcessing() && syncProcessingService.isSyncEnabled()) {
                SyncProcessingResult syncResult = syncProcessingService.processSync(
                        pagwId,
                        processedBundle,
                        request.getTenant(),
                        request.getAuthenticatedProviderId()
                );
                
                // Handle synchronous result
                if (syncResult.isCompleted()) {
                    // Immediate decision - update tracker and return
                    updateTrackerForSyncCompletion(pagwId, syncResult);
                    
                    if (syncResult.isValid()) {
                        log.info("Synchronous processing completed: pagwId={}, disposition={}, timeMs={}", 
                                pagwId, syncResult.getDisposition(), syncResult.getProcessingTimeMs());
                        return buildSyncResponse(pagwId, syncResult);
                    } else {
                        // Validation error - return OperationOutcome
                        log.warn("Validation errors: pagwId={}, errors={}", pagwId, syncResult.getValidationErrors().size());
                        return buildValidationErrorResponse(pagwId, syncResult);
                    }
                } else {
                    // Pended - queue for async processing and return pended response
                    // CRITICAL: Check if already sync processed to prevent race condition
                    // The sync task may have completed between timeout and this check
                    log.info("Request pended, queueing for async: pagwId={}, lastStage={}", 
                            pagwId, syncResult.getLastStage());
                    boolean queued = tryQueueForAsyncProcessing(pagwId, processedBundle, request, requestBucket, rawKey, messageId);
                    if (!queued) {
                        log.warn("Request already processed or queued, skipping async queue: pagwId={}", pagwId);
                    }
                    return buildPendedResponse(pagwId, receivedAt, syncResult);
                }
            } else {
                // Async-only mode (legacy or explicit async request)
                boolean queued = tryQueueForAsyncProcessing(pagwId, processedBundle, request, requestBucket, rawKey, messageId);
                if (queued) {
                    log.info("Request queued for async processing: pagwId={}", pagwId);
                } else {
                    log.warn("Failed to queue request (already processed?): pagwId={}", pagwId);
                }
                return buildQueuedResponse(pagwId, receivedAt);
            }
            
        } catch (Exception e) {
            log.error("Failed to process request: pagwId={}", pagwId, e);
            if (idempotencyService != null) {
                idempotencyService.remove(idempotencyKey);
            }
            throw e;
        }
    }
    
    /**
     * Queue request for asynchronous pipeline processing.
     * Uses atomic database update to prevent race conditions.
     * 
     * @return true if successfully queued, false if already processed or queued
     */
    private boolean tryQueueForAsyncProcessing(
            String pagwId, 
            String processedBundle, 
            PasRequest request,
            String requestBucket,
            String rawKey,
            String messageId) {
        
        // Atomic check-and-set: Only queue if not already sync_processed or async_queued
        boolean canQueue = requestTrackerService.tryMarkAsyncQueued(pagwId);
        
        if (!canQueue) {
            log.debug("Cannot queue pagwId={}: already processed or queued", pagwId);
            return false;
        }
        
        PagwMessage message = PagwMessage.builder()
                .messageId(messageId)
                .pagwId(pagwId)
                .idempotencyKey(request.getIdempotencyKey())
                .schemaVersion("v1")
                .source("pasorchestrator")
                .stage(RequestTracker.STAGE_REQUEST_PARSER)
                .payloadBucket(requestBucket)
                .payloadKey(rawKey)
                .meta(PagwMessage.MessageMeta.builder()
                        .tenant(request.getTenant())
                        .receivedAt(Instant.now())
                        .correlationId(request.getCorrelationId())
                        .build())
                .build();
        
        outboxService.writeOutbox(properties.getQueues().getRequestParser(), message);
        return true;
    }
    
    /**
     * Queue request for asynchronous pipeline processing.
     * @deprecated Use tryQueueForAsyncProcessing for race-condition safety
     */
    @Deprecated
    private void queueForAsyncProcessing(
            String pagwId, 
            String processedBundle, 
            PasRequest request,
            String requestBucket,
            String rawKey,
            String messageId) {
        
        PagwMessage message = PagwMessage.builder()
                .messageId(messageId)
                .pagwId(pagwId)
                .idempotencyKey(request.getIdempotencyKey())
                .schemaVersion("v1")
                .source("pasorchestrator")
                .stage(RequestTracker.STAGE_REQUEST_PARSER)
                .payloadBucket(requestBucket)
                .payloadKey(rawKey)
                .meta(PagwMessage.MessageMeta.builder()
                        .tenant(request.getTenant())
                        .receivedAt(Instant.now())
                        .correlationId(request.getCorrelationId())
                        .build())
                .build();
        
        outboxService.writeOutbox(properties.getQueues().getRequestParser(), message);
    }
    
    /**
     * Update request tracker for synchronous completion.
     */
    private void updateTrackerForSyncCompletion(String pagwId, SyncProcessingResult result) {
        String status = result.isValid() ? 
                (result.getDisposition() == SyncProcessingResult.Disposition.PENDED ? 
                        RequestTracker.STATUS_RECEIVED : RequestTracker.STATUS_COMPLETED) :
                RequestTracker.STATUS_ERROR;
        
        requestTrackerService.updateStatus(pagwId, status, "SYNC_PROCESSING", null);
        if (result.isCompleted() && result.isValid()) {
            requestTrackerService.updateCompletedAt(pagwId, Instant.now());
        }
    }
    
    /**
     * Get status of a request.
     */
    public PasResponse getStatus(String pagwId) {
        Optional<RequestTracker> trackerOpt = requestTrackerService.findByPagwId(pagwId);
        
        if (trackerOpt.isEmpty()) {
            return PasResponse.builder()
                    .pagwId(pagwId)
                    .status("NOT_FOUND")
                    .message("Request not found")
                    .build();
        }
        
        RequestTracker tracker = trackerOpt.get();
        
        return PasResponse.builder()
                .pagwId(pagwId)
                .status(tracker.getStatus())
                .stage(tracker.getLastStage())
                .message(getStatusMessage(tracker.getStatus()))
                .timestamp(tracker.getUpdatedAt())
                .finalS3Bucket(tracker.getFinalS3Bucket())
                .finalS3Key(tracker.getFinalS3Key())
                .build();
    }
    
    private PasResponse buildQueuedResponse(String pagwId, Instant receivedAt) {
        return PasResponse.builder()
                .resourceType("ClaimResponse")
                .pagwId(pagwId)
                .status("queued")
                .message("Request received and queued for processing. Use GET /pas/v1/status/" + pagwId + " to check status.")
                .timestamp(receivedAt)
                .build();
    }
    
    private PasResponse buildDuplicateResponse(String idempotencyKey) {
        return PasResponse.builder()
                .resourceType("ClaimResponse")
                .status("duplicate")
                .message("Duplicate request detected with idempotency key: " + idempotencyKey)
                .timestamp(Instant.now())
                .build();
    }
    
    private String getStatusMessage(String status) {
        return switch (status) {
            case RequestTracker.STATUS_RECEIVED -> "Request received and queued";
            case RequestTracker.STATUS_PARSING -> "Parsing FHIR bundle";
            case RequestTracker.STATUS_VALIDATING -> "Validating business rules";
            case RequestTracker.STATUS_ENRICHING -> "Enriching request data";
            case RequestTracker.STATUS_PROCESSING_ATTACHMENTS -> "Processing attachments";
            case RequestTracker.STATUS_MAPPING -> "Converting to canonical format";
            case RequestTracker.STATUS_CALLING_DOWNSTREAM -> "Calling downstream systems";
            case RequestTracker.STATUS_BUILDING_RESPONSE -> "Building final response";
            case RequestTracker.STATUS_COMPLETED -> "Processing completed";
            case RequestTracker.STATUS_ERROR, RequestTracker.STATUS_FAILED -> "Processing failed";
            default -> "Processing in progress";
        };
    }
    
    /**
     * Build response for synchronous completion.
     */
    private PasResponse buildSyncResponse(String pagwId, SyncProcessingResult result) {
        return PasResponse.builder()
                .resourceType("ClaimResponse")
                .pagwId(pagwId)
                .status(result.getDisposition().getFhirCode())
                .disposition(result.getDisposition().getDescription())
                .message("Prior authorization " + result.getDisposition().getDescription().toLowerCase())
                .claimResponseBundle(result.getClaimResponseBundle())
                .payerTrackingId(result.getPayerTrackingId())
                .timestamp(result.getProcessedAt())
                .processingTimeMs(result.getProcessingTimeMs())
                .build();
    }
    
    /**
     * Build response for pended requests (Da Vinci PAS IG compliant).
     * Pended = decision cannot be made within 15 seconds, will notify via subscription.
     */
    private PasResponse buildPendedResponse(String pagwId, Instant receivedAt, SyncProcessingResult result) {
        return PasResponse.builder()
                .resourceType("ClaimResponse")
                .pagwId(pagwId)
                .status("pended")
                .disposition(SyncProcessingResult.Disposition.PENDED.getDescription())
                .message("Request requires additional review. You will be notified when a decision is made. " +
                        "Use $inquiry operation with tracking ID: " + pagwId)
                .payerTrackingId(pagwId)
                .timestamp(receivedAt)
                .processingTimeMs(result.getProcessingTimeMs())
                .build();
    }
    
    /**
     * Build OperationOutcome response for validation errors (Da Vinci PAS IG compliant).
     */
    private PasResponse buildValidationErrorResponse(String pagwId, SyncProcessingResult result) {
        StringBuilder errorDetails = new StringBuilder("Validation failed: ");
        for (SyncProcessingResult.ValidationError error : result.getValidationErrors()) {
            errorDetails.append(String.format("[%s] %s at %s; ", 
                    error.getCode(), error.getMessage(), error.getLocation()));
        }
        
        return PasResponse.builder()
                .resourceType("OperationOutcome")
                .pagwId(pagwId)
                .status("error")
                .message(errorDetails.toString().trim())
                .validationErrors(result.getValidationErrors())
                .timestamp(Instant.now())
                .processingTimeMs(result.getProcessingTimeMs())
                .build();
    }
}
