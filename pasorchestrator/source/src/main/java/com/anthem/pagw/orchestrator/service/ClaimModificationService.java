package com.anthem.pagw.orchestrator.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.model.RequestTracker;
import com.anthem.pagw.core.service.*;
import com.anthem.pagw.core.util.PagwIdGenerator;
import com.anthem.pagw.orchestrator.model.PasRequest;
import com.anthem.pagw.orchestrator.model.PasResponse;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult.Disposition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling Prior Authorization updates and cancellations.
 * 
 * Per Da Vinci PAS IG:
 * - Updates are submitted as new Claim with Claim.related.claim pointing to original
 * - Cancel uses certificationType extension with code "3"
 * - Update uses certificationType extension with code "4"
 * 
 * X12 278 Certificate Action Codes:
 * - 1 = Initial (new request)
 * - 3 = Cancel
 * - 4 = Update/Modify
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-pas/specification.html#updating-authorization-requests">Update/Cancel</a>
 */
@Service
public class ClaimModificationService {
    
    private static final Logger log = LoggerFactory.getLogger(ClaimModificationService.class);
    
    // Certificate Action Codes (X12 278 HCR01-2)
    public static final String CERT_TYPE_INITIAL = "1";
    public static final String CERT_TYPE_CANCEL = "3";
    public static final String CERT_TYPE_UPDATE = "4";
    
    private final RequestTrackerService requestTrackerService;
    private final S3Service s3Service;
    private final OutboxService outboxService;
    private final AuditService auditService;
    @Nullable
    private final PhiEncryptionService phiEncryptionService;
    private final ObjectMapper objectMapper;
    private final PagwProperties properties;
    
    public ClaimModificationService(
            RequestTrackerService requestTrackerService,
            S3Service s3Service,
            OutboxService outboxService,
            AuditService auditService,
            @Nullable PhiEncryptionService phiEncryptionService,
            ObjectMapper objectMapper,
            PagwProperties properties) {
        this.requestTrackerService = requestTrackerService;
        this.s3Service = s3Service;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.phiEncryptionService = phiEncryptionService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }
    
    /**
     * Process a cancel request for an existing prior authorization.
     * 
     * @param request The cancel request with relatedClaimId
     * @return PasResponse confirming cancellation or error
     */
    @Transactional
    public PasResponse processCancel(PasRequest request) {
        log.info("Processing cancel request: relatedClaimId={}, tenant={}", 
                request.getRelatedClaimId(), request.getTenant());
        
        // Validate related claim exists
        Optional<RequestTracker> originalTracker = findOriginalClaim(request.getRelatedClaimId());
        if (originalTracker.isEmpty()) {
            return buildNotFoundResponse(request.getRelatedClaimId(), "cancel");
        }
        
        RequestTracker original = originalTracker.get();
        
        // Check if already cancelled
        if (RequestTracker.STATUS_CANCELLED.equals(original.getStatus())) {
            return buildAlreadyCancelledResponse(original);
        }
        
        // Check if can be cancelled (only pending/approved can be cancelled)
        if (!canBeCancelled(original.getStatus())) {
            return buildCannotCancelResponse(original);
        }
        
        // Generate new PAGW ID for the cancel request
        String pagwId = PagwIdGenerator.generate();
        Instant receivedAt = Instant.now();
        
        // Create the cancel request bundle
        String cancelBundle = buildCancelBundle(request.getFhirBundle(), original, pagwId);
        
        // Store and process
        return processModification(pagwId, cancelBundle, request, original, 
                RequestTracker.TYPE_CANCEL, CERT_TYPE_CANCEL, receivedAt);
    }
    
    /**
     * Process an update request for an existing prior authorization.
     * 
     * @param request The update request with relatedClaimId and updated FHIR bundle
     * @return PasResponse confirming update submission or error
     */
    @Transactional
    public PasResponse processUpdate(PasRequest request) {
        log.info("Processing update request: relatedClaimId={}, tenant={}", 
                request.getRelatedClaimId(), request.getTenant());
        
        // Validate related claim exists
        Optional<RequestTracker> originalTracker = findOriginalClaim(request.getRelatedClaimId());
        if (originalTracker.isEmpty()) {
            return buildNotFoundResponse(request.getRelatedClaimId(), "update");
        }
        
        RequestTracker original = originalTracker.get();
        
        // Check if can be updated
        if (!canBeUpdated(original.getStatus())) {
            return buildCannotUpdateResponse(original);
        }
        
        // Generate new PAGW ID for the update request
        String pagwId = PagwIdGenerator.generate();
        Instant receivedAt = Instant.now();
        
        // Enhance the bundle with related claim reference
        String updateBundle = enhanceWithRelatedClaim(request.getFhirBundle(), original, pagwId);
        
        // Store and process
        return processModification(pagwId, updateBundle, request, original,
                RequestTracker.TYPE_UPDATE, CERT_TYPE_UPDATE, receivedAt);
    }
    
    /**
     * Find the original claim by PAGW ID or external reference.
     */
    private Optional<RequestTracker> findOriginalClaim(String relatedClaimId) {
        if (relatedClaimId == null || relatedClaimId.isBlank()) {
            return Optional.empty();
        }
        
        // Try PAGW ID first
        Optional<RequestTracker> tracker = requestTrackerService.findByPagwId(relatedClaimId);
        if (tracker.isPresent()) {
            return tracker;
        }
        
        // Try external reference
        return requestTrackerService.findByExternalId(relatedClaimId);
    }
    
    /**
     * Check if claim can be cancelled.
     */
    private boolean canBeCancelled(String status) {
        return switch (status) {
            case RequestTracker.STATUS_RECEIVED,
                 RequestTracker.STATUS_PARSING,
                 RequestTracker.STATUS_VALIDATING,
                 RequestTracker.STATUS_ENRICHING,
                 RequestTracker.STATUS_PROCESSING_ATTACHMENTS,
                 RequestTracker.STATUS_MAPPING,
                 RequestTracker.STATUS_CALLING_DOWNSTREAM,
                 RequestTracker.STATUS_COMPLETED -> true;
            default -> false;
        };
    }
    
    /**
     * Check if claim can be updated.
     */
    private boolean canBeUpdated(String status) {
        return switch (status) {
            case RequestTracker.STATUS_COMPLETED -> true; // Only completed (approved) can be updated
            default -> false;
        };
    }
    
    /**
     * Process a modification (cancel or update).
     */
    private PasResponse processModification(
            String pagwId,
            String bundle,
            PasRequest request,
            RequestTracker original,
            String requestType,
            String certificationType,
            Instant receivedAt) {
        
        try {
            // Store the modification request in S3 using standardized path
            String requestBucket = properties.getAws().getS3().getRequestBucket();
            String rawKey = PagwProperties.S3Paths.raw(pagwId);
            s3Service.putObject(requestBucket, rawKey, bundle);
            
            // Create tracker for modification request
            RequestTracker modTracker = RequestTracker.builder()
                    .pagwId(pagwId)
                    .status(RequestTracker.STATUS_RECEIVED)
                    .tenant(request.getTenant())
                    .sourceSystem("APIGEE")
                    .requestType(requestType)
                    .lastStage("ORCHESTRATOR")
                    .nextStage(RequestTracker.STAGE_REQUEST_PARSER)
                    .rawS3Bucket(requestBucket)
                    .rawS3Key(rawKey)
                    .containsPhi(true)
                    .idempotencyKey(pagwId)
                    .receivedAt(receivedAt)
                    .externalRequestId(original.getExternalRequestId()) // Link to original
                    .build();
            
            requestTrackerService.create(modTracker);
            
            // If cancel, update the original tracker status
            if (RequestTracker.TYPE_CANCEL.equals(requestType)) {
                requestTrackerService.updateStatus(
                        original.getPagwId(), 
                        RequestTracker.STATUS_CANCELLED, 
                        "CANCELLED_BY_" + pagwId,
                        null);
                auditService.logRequestCancelled(original.getPagwId(), pagwId, request.getCorrelationId());
            }
            
            // Queue for processing
            PagwMessage message = PagwMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .pagwId(pagwId)
                    .idempotencyKey(pagwId)
                    .schemaVersion("v1")
                    .source("pasorchestrator")
                    .stage(RequestTracker.STAGE_REQUEST_PARSER)
                    .payloadPointer(PagwMessage.PayloadPointer.builder()
                            .s3Bucket(requestBucket)
                            .s3Key(rawKey)
                            .build())
                    .meta(PagwMessage.MessageMeta.builder()
                            .tenant(request.getTenant())
                            .receivedAt(receivedAt)
                            .correlationId(request.getCorrelationId())
                            .build())
                    .build();
            
            outboxService.writeOutbox(properties.getQueues().getRequestParser(), message);
            
            // Log audit
            if (RequestTracker.TYPE_UPDATE.equals(requestType)) {
                auditService.logRequestUpdated(original.getPagwId(), pagwId, request.getCorrelationId());
            }
            
            log.info("{} request queued: pagwId={}, originalPagwId={}", 
                    requestType, pagwId, original.getPagwId());
            
            return buildModificationResponse(pagwId, original, requestType, receivedAt);
            
        } catch (Exception e) {
            log.error("Failed to process {} request: relatedClaimId={}", 
                    requestType, request.getRelatedClaimId(), e);
            throw e;
        }
    }
    
    /**
     * Build a cancel bundle with certificationType = 3.
     */
    private String buildCancelBundle(String originalBundle, RequestTracker original, String newPagwId) {
        try {
            ObjectNode bundle;
            if (originalBundle != null && !originalBundle.isBlank()) {
                bundle = (ObjectNode) objectMapper.readTree(originalBundle);
            } else {
                bundle = objectMapper.createObjectNode();
                bundle.put("resourceType", "Bundle");
                bundle.put("type", "collection");
            }
            
            bundle.put("id", newPagwId);
            
            // Add or update identifier
            ObjectNode identifier = bundle.putObject("identifier");
            identifier.put("system", "urn:pagw:bundle");
            identifier.put("value", newPagwId);
            
            // Find or create Claim entry and add related claim + certificationType
            ArrayNode entries = bundle.has("entry") ? (ArrayNode) bundle.get("entry") : bundle.putArray("entry");
            
            boolean claimFound = false;
            for (JsonNode entry : entries) {
                JsonNode resource = entry.path("resource");
                if ("Claim".equals(resource.path("resourceType").asText())) {
                    addCancelExtensions((ObjectNode) resource, original);
                    claimFound = true;
                    break;
                }
            }
            
            if (!claimFound) {
                // Create minimal cancel Claim
                ObjectNode entry = entries.addObject();
                entry.put("fullUrl", "urn:uuid:" + UUID.randomUUID());
                ObjectNode claim = entry.putObject("resource");
                claim.put("resourceType", "Claim");
                claim.put("id", UUID.randomUUID().toString());
                claim.put("status", "cancelled");
                claim.put("use", "preauthorization");
                addCancelExtensions(claim, original);
            }
            
            return objectMapper.writeValueAsString(bundle);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to build cancel bundle", e);
            throw new RuntimeException("Failed to build cancel bundle", e);
        }
    }
    
    /**
     * Add cancel-specific extensions to Claim.
     */
    private void addCancelExtensions(ObjectNode claim, RequestTracker original) {
        // Add related claim reference
        ArrayNode related = claim.has("related") ? (ArrayNode) claim.get("related") : claim.putArray("related");
        ObjectNode relatedClaim = related.addObject();
        relatedClaim.put("relationship", "prior");
        ObjectNode claimRef = relatedClaim.putObject("claim");
        claimRef.put("reference", "Claim/" + original.getPagwId());
        if (original.getExternalRequestId() != null) {
            ObjectNode identifier = claimRef.putObject("identifier");
            identifier.put("system", "urn:payer:preauth");
            identifier.put("value", original.getExternalRequestId());
        }
        
        // Add certificationType extension (3 = Cancel)
        ArrayNode extensions = claim.has("extension") ? (ArrayNode) claim.get("extension") : claim.putArray("extension");
        ObjectNode certTypeExt = extensions.addObject();
        certTypeExt.put("url", "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType");
        ObjectNode coding = certTypeExt.putObject("valueCodeableConcept").putArray("coding").addObject();
        coding.put("system", "https://codesystem.x12.org/005010/1322");
        coding.put("code", CERT_TYPE_CANCEL);
        coding.put("display", "Cancel");
    }
    
    /**
     * Enhance update bundle with related claim reference.
     */
    private String enhanceWithRelatedClaim(String bundle, RequestTracker original, String newPagwId) {
        try {
            ObjectNode bundleNode = (ObjectNode) objectMapper.readTree(bundle);
            bundleNode.put("id", newPagwId);
            
            // Update identifier
            ObjectNode identifier = bundleNode.putObject("identifier");
            identifier.put("system", "urn:pagw:bundle");
            identifier.put("value", newPagwId);
            
            // Find Claim and add related + certificationType
            ArrayNode entries = (ArrayNode) bundleNode.get("entry");
            if (entries != null) {
                for (JsonNode entry : entries) {
                    JsonNode resource = entry.path("resource");
                    if ("Claim".equals(resource.path("resourceType").asText())) {
                        addUpdateExtensions((ObjectNode) resource, original);
                        break;
                    }
                }
            }
            
            return objectMapper.writeValueAsString(bundleNode);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to enhance update bundle", e);
            throw new RuntimeException("Failed to enhance update bundle", e);
        }
    }
    
    /**
     * Add update-specific extensions to Claim.
     */
    private void addUpdateExtensions(ObjectNode claim, RequestTracker original) {
        // Add related claim reference
        ArrayNode related = claim.has("related") ? (ArrayNode) claim.get("related") : claim.putArray("related");
        ObjectNode relatedClaim = related.addObject();
        relatedClaim.put("relationship", "prior");
        ObjectNode claimRef = relatedClaim.putObject("claim");
        claimRef.put("reference", "Claim/" + original.getPagwId());
        if (original.getExternalRequestId() != null) {
            ObjectNode identifier = claimRef.putObject("identifier");
            identifier.put("system", "urn:payer:preauth");
            identifier.put("value", original.getExternalRequestId());
        }
        
        // Add certificationType extension (4 = Update)
        ArrayNode extensions = claim.has("extension") ? (ArrayNode) claim.get("extension") : claim.putArray("extension");
        ObjectNode certTypeExt = extensions.addObject();
        certTypeExt.put("url", "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-certificationType");
        ObjectNode coding = certTypeExt.putObject("valueCodeableConcept").putArray("coding").addObject();
        coding.put("system", "https://codesystem.x12.org/005010/1322");
        coding.put("code", CERT_TYPE_UPDATE);
        coding.put("display", "Revised");
    }
    
    // Response builders
    
    private PasResponse buildModificationResponse(String pagwId, RequestTracker original, String type, Instant timestamp) {
        String action = RequestTracker.TYPE_CANCEL.equals(type) ? "cancelled" : "updated";
        return PasResponse.builder()
                .resourceType("ClaimResponse")
                .pagwId(pagwId)
                .status("queued")
                .disposition(RequestTracker.TYPE_CANCEL.equals(type) ? "Cancellation Requested" : "Update Requested")
                .message(String.format("Prior authorization %s request submitted. Original: %s, New tracking ID: %s",
                        action, original.getPagwId(), pagwId))
                .payerTrackingId(pagwId)
                .timestamp(timestamp)
                .build();
    }
    
    private PasResponse buildNotFoundResponse(String relatedClaimId, String action) {
        return PasResponse.builder()
                .resourceType("OperationOutcome")
                .status("error")
                .errorCode("NOT_FOUND")
                .errorMessage(String.format("Cannot %s: Original prior authorization not found: %s", 
                        action, relatedClaimId))
                .timestamp(Instant.now())
                .build();
    }
    
    private PasResponse buildAlreadyCancelledResponse(RequestTracker original) {
        return PasResponse.builder()
                .resourceType("OperationOutcome")
                .pagwId(original.getPagwId())
                .status("error")
                .errorCode("ALREADY_CANCELLED")
                .errorMessage("Prior authorization has already been cancelled")
                .timestamp(Instant.now())
                .build();
    }
    
    private PasResponse buildCannotCancelResponse(RequestTracker original) {
        return PasResponse.builder()
                .resourceType("OperationOutcome")
                .pagwId(original.getPagwId())
                .status("error")
                .errorCode("CANNOT_CANCEL")
                .errorMessage(String.format("Cannot cancel prior authorization in status: %s", original.getStatus()))
                .timestamp(Instant.now())
                .build();
    }
    
    private PasResponse buildCannotUpdateResponse(RequestTracker original) {
        return PasResponse.builder()
                .resourceType("OperationOutcome")
                .pagwId(original.getPagwId())
                .status("error")
                .errorCode("CANNOT_UPDATE")
                .errorMessage(String.format("Cannot update prior authorization in status: %s. Only completed authorizations can be updated.",
                        original.getStatus()))
                .timestamp(Instant.now())
                .build();
    }
}
