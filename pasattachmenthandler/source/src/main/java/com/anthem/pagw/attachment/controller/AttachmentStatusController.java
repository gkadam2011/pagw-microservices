package com.anthem.pagw.attachment.controller;

import com.anthem.pagw.attachment.model.AttachmentCompletionStatus;
import com.anthem.pagw.attachment.service.AttachmentHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for attachment status operations.
 * 
 * Provides endpoints to check attachment processing status
 * and completion for PAS requests.
 */
@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentStatusController {
    
    private static final Logger log = LoggerFactory.getLogger(AttachmentStatusController.class);
    
    private final AttachmentHandlerService attachmentService;
    
    public AttachmentStatusController(AttachmentHandlerService attachmentService) {
        this.attachmentService = attachmentService;
    }
    
    /**
     * Get completion status for all attachments of a request.
     * 
     * @param pagwId The PAGW request ID
     * @return AttachmentCompletionStatus with counts and completion flag
     */
    @GetMapping("/status/{pagwId}")
    public ResponseEntity<AttachmentCompletionStatus> getCompletionStatus(@PathVariable String pagwId) {
        log.debug("Getting attachment completion status: pagwId={}", pagwId);
        
        AttachmentCompletionStatus status = attachmentService.getCompletionStatus(pagwId);
        
        log.info("Attachment completion status: {}", status);
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * Quick check if all attachments are complete.
     * Returns simple boolean for efficient polling.
     * 
     * @param pagwId The PAGW request ID
     * @return { "complete": true/false, "pendingCount": n }
     */
    @GetMapping("/complete/{pagwId}")
    public ResponseEntity<Map<String, Object>> isComplete(@PathVariable String pagwId) {
        boolean complete = attachmentService.areAllAttachmentsComplete(pagwId);
        int pendingCount = attachmentService.getPendingAttachmentCount(pagwId);
        
        return ResponseEntity.ok(Map.of(
            "pagwId", pagwId,
            "complete", complete,
            "pendingCount", pendingCount
        ));
    }
    
    /**
     * Get list of pending attachment IDs for a request.
     * Useful for debugging which attachments are blocking completion.
     * 
     * @param pagwId The PAGW request ID
     * @return List of attachment IDs still pending
     */
    @GetMapping("/pending/{pagwId}")
    public ResponseEntity<Map<String, Object>> getPendingAttachments(@PathVariable String pagwId) {
        List<String> pendingIds = attachmentService.getPendingAttachmentIds(pagwId);
        
        return ResponseEntity.ok(Map.of(
            "pagwId", pagwId,
            "pendingCount", pendingIds.size(),
            "pendingAttachmentIds", pendingIds
        ));
    }
}
