package com.anthem.pagw.attachment.model;

import java.time.Instant;

/**
 * Represents the completion status of all attachments for a PAS request.
 * Used to determine when the pipeline can proceed to the next stage.
 */
public class AttachmentCompletionStatus {
    
    private String pagwId;
    private int totalCount;
    private int completedCount;
    private int pendingCount;
    private int failedCount;
    private boolean allComplete;
    private Instant checkedAt;
    
    private AttachmentCompletionStatus() {}
    
    public static Builder builder() {
        return new Builder();
    }
    
    // Getters
    public String getPagwId() { return pagwId; }
    public int getTotalCount() { return totalCount; }
    public int getCompletedCount() { return completedCount; }
    public int getPendingCount() { return pendingCount; }
    public int getFailedCount() { return failedCount; }
    public boolean isAllComplete() { return allComplete; }
    public Instant getCheckedAt() { return checkedAt; }
    
    /**
     * Check if processing can proceed (all complete or all failed).
     * Returns true if no attachments are pending.
     */
    public boolean canProceed() {
        return pendingCount == 0;
    }
    
    /**
     * Check if there were any failures.
     */
    public boolean hasFailures() {
        return failedCount > 0;
    }
    
    /**
     * Get success rate as percentage.
     */
    public double getSuccessRate() {
        if (totalCount == 0) return 100.0;
        return (completedCount * 100.0) / totalCount;
    }
    
    @Override
    public String toString() {
        return String.format(
            "AttachmentCompletionStatus{pagwId='%s', total=%d, completed=%d, pending=%d, failed=%d, allComplete=%s}",
            pagwId, totalCount, completedCount, pendingCount, failedCount, allComplete
        );
    }
    
    public static class Builder {
        private final AttachmentCompletionStatus status = new AttachmentCompletionStatus();
        
        public Builder pagwId(String pagwId) {
            status.pagwId = pagwId;
            return this;
        }
        
        public Builder totalCount(int totalCount) {
            status.totalCount = totalCount;
            return this;
        }
        
        public Builder completedCount(int completedCount) {
            status.completedCount = completedCount;
            return this;
        }
        
        public Builder pendingCount(int pendingCount) {
            status.pendingCount = pendingCount;
            return this;
        }
        
        public Builder failedCount(int failedCount) {
            status.failedCount = failedCount;
            return this;
        }
        
        public Builder allComplete(boolean allComplete) {
            status.allComplete = allComplete;
            return this;
        }
        
        public Builder checkedAt(Instant checkedAt) {
            status.checkedAt = checkedAt;
            return this;
        }
        
        public AttachmentCompletionStatus build() {
            return status;
        }
    }
}
