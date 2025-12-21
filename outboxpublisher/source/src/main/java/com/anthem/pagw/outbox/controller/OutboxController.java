package com.anthem.pagw.outbox.controller;

import com.anthem.pagw.outbox.service.OutboxPublisherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for outbox publisher operations.
 */
@RestController
@RequestMapping("/outbox")
public class OutboxController {
    
    private final OutboxPublisherService publisherService;
    
    public OutboxController(OutboxPublisherService publisherService) {
        this.publisherService = publisherService;
    }
    
    /**
     * Get outbox statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<OutboxPublisherService.OutboxStats> getStats() {
        return ResponseEntity.ok(publisherService.getStats());
    }
    
    /**
     * Manually trigger outbox publishing.
     */
    @PostMapping("/publish")
    public ResponseEntity<OutboxPublisherService.PublishResult> triggerPublish() {
        OutboxPublisherService.PublishResult result = publisherService.publishNow();
        return ResponseEntity.ok(result);
    }
    
    /**
     * Health check.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
