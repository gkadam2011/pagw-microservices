package com.anthem.pagw.outbox.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.EventTracker;
import com.anthem.pagw.core.model.OutboxEntry;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.SqsService;
import com.anthem.pagw.core.util.JsonUtils;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Outbox Publisher Service with High Availability Support.
 * 
 * Polls the outbox table and publishes messages to SQS queues.
 * Uses ShedLock for distributed locking to ensure only ONE pod
 * processes outbox entries at a time, preventing duplicates.
 * 
 * <p>HA Deployment:</p>
 * <ul>
 *   <li>Multiple pods can run simultaneously for availability</li>
 *   <li>ShedLock ensures only one pod publishes at a time</li>
 *   <li>If the active pod fails, another will take over automatically</li>
 *   <li>Lock timeout ensures no stuck locks from crashed pods</li>
 * </ul>
 */
@Service
public class OutboxPublisherService {
    
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);
    
    private final OutboxService outboxService;
    private final SqsService sqsService;
    private final EventTrackerService eventTrackerService;
    private final PagwProperties properties;
    
    public OutboxPublisherService(
            OutboxService outboxService,
            SqsService sqsService,
            EventTrackerService eventTrackerService,
            PagwProperties properties) {
        this.outboxService = outboxService;
        this.sqsService = sqsService;
        this.eventTrackerService = eventTrackerService;
        this.properties = properties;
    }
    
    /**
     * Scheduled task to publish outbox entries.
     * Runs every second by default.
     * 
     * Uses ShedLock to ensure only one instance runs at a time:
     * - lockAtLeastFor: Minimum time to hold lock (prevents rapid re-execution)
     * - lockAtMostFor: Maximum time to hold lock (prevents stuck locks)
     */
    @Scheduled(fixedDelayString = "${pagw.outbox.publish-interval-ms:1000}")
    @SchedulerLock(
            name = "outbox-publisher",
            lockAtLeastFor = "PT1S",   // Hold lock for at least 1 second
            lockAtMostFor = "PT30S"    // Release lock after 30 seconds max (failsafe)
    )
    public void publishOutboxEntries() {
        int batchSize = properties.getOutbox().getBatchSize();
        int maxRetries = properties.getOutbox().getMaxRetries();
        
        List<OutboxEntry> entries = outboxService.fetchUnpublished(batchSize);
        
        if (entries.isEmpty()) {
            return;
        }
        
        log.info("Processing {} outbox entries", entries.size());
        
        int published = 0;
        int failed = 0;
        
        for (OutboxEntry entry : entries) {
            long startTime = System.currentTimeMillis();
            String pagwId = null;
            String tenant = null;
            
            try {
                // Extract pagwId and tenant from message
                PagwMessage message = JsonUtils.fromJson(entry.getPayload(), PagwMessage.class);
                pagwId = message.getPagwId();
                // Try top-level tenant first, then fall back to meta.tenant
                tenant = message.getTenant();
                if (tenant == null && message.getMeta() != null) {
                    tenant = message.getMeta().getTenant();
                }
                // Final fallback to prevent NOT NULL constraint violations
                if (tenant == null) {
                    tenant = "UNKNOWN";
                    log.warn("No tenant found in message for pagwId={}, using default: UNKNOWN", pagwId);
                }
                
                // Event tracking: PUBLISH_START
                eventTrackerService.logStageStart(pagwId, tenant, "OUTBOX_PUBLISHER", EventTracker.EVENT_PUBLISH_START, null);
                
                // Check if max retries exceeded
                if (entry.getRetryCount() >= maxRetries) {
                    log.error("Outbox entry exceeded max retries: id={}, destinationQueue={}, retries={}",
                            entry.getId(), entry.getDestinationQueue(), entry.getRetryCount());
                    // Could move to a failed/operator queue here
                    continue;
                }
                
                // Publish to SQS
                String queueUrl = getQueueUrl(entry.getDestinationQueue());
                sqsService.sendMessage(queueUrl, message);
                
                // Mark as published
                outboxService.markCompleted(entry.getId());
                published++;
                
                // Event tracking: PUBLISH_OK
                long duration = System.currentTimeMillis() - startTime;
                String metadata = String.format("{\"destinationQueue\":\"%s\",\"retryCount\":%d}",
                    entry.getDestinationQueue(), entry.getRetryCount());
                eventTrackerService.logStageComplete(
                    pagwId, tenant, "OUTBOX_PUBLISHER", EventTracker.EVENT_PUBLISH_OK, duration, metadata
                );
                
                log.debug("Published outbox entry: id={}, destinationQueue={}", entry.getId(), entry.getDestinationQueue());
                
            } catch (Exception e) {
                log.error("Failed to publish outbox entry: id={}, destinationQueue={}, error={}",
                        entry.getId(), entry.getDestinationQueue(), e.getMessage());
                
                // Event tracking: PUBLISH_FAIL (retryable)
                if (pagwId != null) {
                    eventTrackerService.logStageError(
                        pagwId, tenant, "OUTBOX_PUBLISHER", EventTracker.EVENT_PUBLISH_FAIL,
                        "PUBLISH_EXCEPTION", e.getMessage(), true, java.time.Instant.now().plusSeconds(300)
                    );
                }
                
                outboxService.incrementRetry(entry.getId(), e.getMessage());
                failed++;
            }
        }
        
        log.info("Outbox publishing complete: published={}, failed={}", published, failed);
    }
    
    /**
     * Get SQS queue URL from destination queue.
     * If the destination is already a full URL, use it directly.
     * Otherwise, resolve the queue name to a URL via SQS API.
     */
    private String getQueueUrl(String destinationQueue) {
        // If destinationQueue is already a full URL (http:// or https://), use it directly
        if (destinationQueue != null && (destinationQueue.startsWith("http://") || destinationQueue.startsWith("https://"))) {
            return destinationQueue;
        }
        // Otherwise, treat it as a queue name and resolve via SQS API
        return sqsService.getQueueUrl(destinationQueue);
    }
    
    /**
     * Manual trigger for publishing (for testing/operations).
     */
    public PublishResult publishNow() {
        List<OutboxEntry> entries = outboxService.fetchUnpublished(
                properties.getOutbox().getBatchSize());
        
        int published = 0;
        int failed = 0;
        
        for (OutboxEntry entry : entries) {
            try {
                String queueUrl = getQueueUrl(entry.getDestinationQueue());
                PagwMessage message = JsonUtils.fromJson(entry.getPayload(), PagwMessage.class);
                sqsService.sendMessage(queueUrl, message);
                outboxService.markCompleted(entry.getId());
                published++;
            } catch (Exception e) {
                outboxService.incrementRetry(entry.getId(), e.getMessage());
                failed++;
            }
        }
        
        return new PublishResult(published, failed);
    }
    
    /**
     * Get current outbox statistics.
     */
    public OutboxStats getStats() {
        long unpublished = outboxService.getPendingCount();
        long stuck = outboxService.getStuckEntriesCount(properties.getOutbox().getMaxRetries());
        
        return new OutboxStats(unpublished, stuck);
    }
    
    public record PublishResult(int published, int failed) {}
    public record OutboxStats(long unpublished, long stuck) {}
}
