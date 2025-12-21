package com.anthem.pagw.orchestrator.service;

import com.anthem.pagw.core.model.RequestTracker;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.orchestrator.model.InquiryRequest;
import com.anthem.pagw.orchestrator.model.InquiryResponse;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult.Disposition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inquiry Service - implements Da Vinci PAS $inquiry operation.
 * 
 * The $inquiry operation allows providers to check the status of a 
 * previously submitted prior authorization request that returned "pended".
 * 
 * Query methods:
 * 1. By PAGW tracking ID (pagwId)
 * 2. By payer tracking ID (preAuthRef)
 * 3. By patient + provider + service date
 * 4. By Bundle.identifier from original submission
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/OperationDefinition-Claim-inquiry.html">$inquiry</a>
 */
@Service
public class InquiryService {
    
    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);
    
    private final RequestTrackerService requestTrackerService;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper;
    
    public InquiryService(
            RequestTrackerService requestTrackerService,
            S3Service s3Service,
            ObjectMapper objectMapper) {
        this.requestTrackerService = requestTrackerService;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Process an inquiry request.
     * 
     * @param request The inquiry parameters
     * @return InquiryResponse with current authorization status
     */
    public InquiryResponse processInquiry(InquiryRequest request) {
        log.info("Processing inquiry: pagwId={}, preAuthRef={}, bundleId={}", 
                request.getPagwId(), request.getPreAuthRef(), request.getBundleIdentifier());
        
        // Try to find the authorization by various identifiers
        Optional<RequestTracker> trackerOpt = findAuthorization(request);
        
        if (trackerOpt.isEmpty()) {
            return InquiryResponse.notFound(request);
        }
        
        RequestTracker tracker = trackerOpt.get();
        
        // Build the response based on current status
        return buildInquiryResponse(tracker, request);
    }
    
    /**
     * Find authorization by various query parameters.
     */
    private Optional<RequestTracker> findAuthorization(InquiryRequest request) {
        // Priority 1: By PAGW ID
        if (request.getPagwId() != null && !request.getPagwId().isBlank()) {
            return requestTrackerService.findByPagwId(request.getPagwId());
        }
        
        // Priority 2: By payer tracking ID (preAuthRef)
        if (request.getPreAuthRef() != null && !request.getPreAuthRef().isBlank()) {
            return requestTrackerService.findByExternalId(request.getPreAuthRef());
        }
        
        // Priority 3: By Bundle.identifier
        if (request.getBundleIdentifier() != null && !request.getBundleIdentifier().isBlank()) {
            return requestTrackerService.findByIdempotencyKey(request.getBundleIdentifier());
        }
        
        // Priority 4: By patient + provider + date range
        if (request.getPatientId() != null && request.getProviderId() != null) {
            return requestTrackerService.findByPatientAndProvider(
                    request.getPatientId(),
                    request.getProviderId(),
                    request.getServiceDateFrom(),
                    request.getServiceDateTo()
            );
        }
        
        return Optional.empty();
    }
    
    /**
     * Build inquiry response from tracker data.
     */
    private InquiryResponse buildInquiryResponse(RequestTracker tracker, InquiryRequest request) {
        Disposition disposition = mapStatusToDisposition(tracker.getStatus());
        
        InquiryResponse.InquiryResponseBuilder builder = InquiryResponse.builder()
                .pagwId(tracker.getPagwId())
                .preAuthRef(tracker.getExternalRequestId())
                .status(tracker.getStatus())
                .disposition(disposition)
                .dispositionDescription(disposition.getDescription())
                .currentStage(tracker.getLastStage())
                .receivedAt(tracker.getReceivedAt())
                .updatedAt(tracker.getUpdatedAt())
                .completedAt(tracker.getCompletedAt());
        
        // If completed, try to get the final response
        if (RequestTracker.STATUS_COMPLETED.equals(tracker.getStatus())) {
            builder.claimResponseBundle(getCompletedResponse(tracker));
        }
        
        // If still processing, add progress info
        if (isStillProcessing(tracker.getStatus())) {
            builder.estimatedCompletionTime(estimateCompletion(tracker));
            builder.progressPercentage(calculateProgress(tracker));
        }
        
        // If error, include error details
        if (RequestTracker.STATUS_ERROR.equals(tracker.getStatus()) || 
                RequestTracker.STATUS_FAILED.equals(tracker.getStatus())) {
            builder.errorCode(tracker.getLastErrorCode());
            builder.errorMessage(tracker.getLastErrorMsg());
        }
        
        return builder.build();
    }
    
    /**
     * Map internal status to Da Vinci PAS disposition.
     */
    private Disposition mapStatusToDisposition(String status) {
        return switch (status) {
            case RequestTracker.STATUS_COMPLETED -> Disposition.APPROVED; // May need to read actual result
            case RequestTracker.STATUS_ERROR, RequestTracker.STATUS_FAILED -> Disposition.ERROR;
            case RequestTracker.STATUS_CANCELLED -> Disposition.CANCELLED;
            default -> Disposition.PENDED; // Still processing
        };
    }
    
    /**
     * Check if request is still being processed.
     */
    private boolean isStillProcessing(String status) {
        return switch (status) {
            case RequestTracker.STATUS_RECEIVED,
                 RequestTracker.STATUS_PARSING,
                 RequestTracker.STATUS_VALIDATING,
                 RequestTracker.STATUS_ENRICHING,
                 RequestTracker.STATUS_PROCESSING_ATTACHMENTS,
                 RequestTracker.STATUS_MAPPING,
                 RequestTracker.STATUS_CALLING_DOWNSTREAM,
                 RequestTracker.STATUS_BUILDING_RESPONSE -> true;
            default -> false;
        };
    }
    
    /**
     * Calculate progress percentage based on stage.
     */
    private int calculateProgress(RequestTracker tracker) {
        return switch (tracker.getLastStage()) {
            case RequestTracker.STAGE_REQUEST_PARSER -> 15;
            case RequestTracker.STAGE_BUSINESS_VALIDATOR -> 30;
            case RequestTracker.STAGE_ATTACHMENT_HANDLER -> 40;
            case RequestTracker.STAGE_REQUEST_ENRICHER -> 50;
            case RequestTracker.STAGE_CANONICAL_MAPPER -> 65;
            case RequestTracker.STAGE_API_ORCHESTRATOR -> 80;
            case RequestTracker.STAGE_CALLBACK_HANDLER -> 90;
            case RequestTracker.STAGE_RESPONSE_BUILDER -> 95;
            default -> 10;
        };
    }
    
    /**
     * Estimate completion time based on current progress.
     */
    private Instant estimateCompletion(RequestTracker tracker) {
        int progress = calculateProgress(tracker);
        if (progress == 0) return null;
        
        // Simple linear estimation
        long elapsedMs = System.currentTimeMillis() - tracker.getReceivedAt().toEpochMilli();
        long estimatedTotalMs = (elapsedMs * 100) / progress;
        long remainingMs = estimatedTotalMs - elapsedMs;
        
        // Cap at 24 hours
        remainingMs = Math.min(remainingMs, 24 * 60 * 60 * 1000);
        
        return Instant.now().plusMillis(remainingMs);
    }
    
    /**
     * Get completed ClaimResponse bundle from S3.
     */
    private String getCompletedResponse(RequestTracker tracker) {
        if (tracker.getFinalS3Bucket() == null || tracker.getFinalS3Key() == null) {
            return null;
        }
        
        try {
            return s3Service.getObject(tracker.getFinalS3Bucket(), tracker.getFinalS3Key());
        } catch (Exception e) {
            log.warn("Failed to retrieve completed response: pagwId={}", tracker.getPagwId(), e);
            return null;
        }
    }
    
    /**
     * Build FHIR Bundle response for $inquiry operation.
     */
    public String buildFhirInquiryResponse(InquiryResponse response) {
        try {
            ObjectNode bundle = objectMapper.createObjectNode();
            bundle.put("resourceType", "Bundle");
            bundle.put("type", "collection");
            bundle.put("id", UUID.randomUUID().toString());
            bundle.put("timestamp", Instant.now().toString());
            
            ArrayNode entries = bundle.putArray("entry");
            
            // ClaimResponse entry
            ObjectNode entry = entries.addObject();
            entry.put("fullUrl", "urn:uuid:" + UUID.randomUUID());
            
            ObjectNode claimResponse = entry.putObject("resource");
            claimResponse.put("resourceType", "ClaimResponse");
            claimResponse.put("id", response.getPagwId());
            claimResponse.put("status", "active");
            claimResponse.put("outcome", response.getDisposition().getFhirCode());
            
            // Pre-auth reference
            if (response.getPreAuthRef() != null) {
                claimResponse.put("preAuthRef", response.getPreAuthRef());
            }
            
            // Disposition text
            ObjectNode disposition = claimResponse.putObject("disposition");
            disposition.put("text", response.getDispositionDescription());
            
            // Extension for X12 review action code
            ArrayNode extensions = claimResponse.putArray("extension");
            ObjectNode reviewAction = extensions.addObject();
            reviewAction.put("url", "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewAction");
            ObjectNode coding = reviewAction.putObject("valueCodeableConcept").putArray("coding").addObject();
            coding.put("system", "https://codesystem.x12.org/005010/306");
            coding.put("code", response.getDisposition().getX12Code());
            coding.put("display", response.getDispositionDescription());
            
            // Add process notes for pended
            if (response.getDisposition() == Disposition.PENDED) {
                ArrayNode processNotes = claimResponse.putArray("processNote");
                ObjectNode note = processNotes.addObject();
                note.put("number", 1);
                
                StringBuilder noteText = new StringBuilder();
                noteText.append("Request is being processed. ");
                noteText.append("Current stage: ").append(response.getCurrentStage()).append(". ");
                if (response.getProgressPercentage() != null) {
                    noteText.append("Progress: ").append(response.getProgressPercentage()).append("%. ");
                }
                if (response.getEstimatedCompletionTime() != null) {
                    noteText.append("Estimated completion: ").append(response.getEstimatedCompletionTime()).append(".");
                }
                note.put("text", noteText.toString());
            }
            
            // Add error info if failed
            if (response.getDisposition() == Disposition.ERROR && response.getErrorMessage() != null) {
                ArrayNode errors = claimResponse.putArray("error");
                ObjectNode error = errors.addObject();
                ObjectNode errorCode = error.putObject("code");
                errorCode.putArray("coding").addObject()
                        .put("system", "urn:pagw:error")
                        .put("code", response.getErrorCode() != null ? response.getErrorCode() : "PROCESSING_ERROR")
                        .put("display", response.getErrorMessage());
            }
            
            return objectMapper.writeValueAsString(bundle);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to build FHIR inquiry response", e);
            return "{\"error\": \"Failed to build response\"}";
        }
    }
}
