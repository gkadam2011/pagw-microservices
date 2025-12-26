package com.anthem.pagw.core.exception;

/**
 * Exception for conflict/duplicate errors.
 * Returns HTTP 409 Conflict.
 */
public class ConflictException extends PagwException {
    
    private final String conflictType;
    private final String existingResourceId;
    
    public ConflictException(String message) {
        this("DUPLICATE", null, message);
    }
    
    public ConflictException(String conflictType, String existingResourceId, String message) {
        super(ErrorCode.CONFLICT, message, null, null, null, ErrorSeverity.ERROR, 409, null);
        this.conflictType = conflictType;
        this.existingResourceId = existingResourceId;
        this.withContext("conflictType", conflictType);
        if (existingResourceId != null) {
            this.withContext("existingResourceId", existingResourceId);
        }
    }
    
    public String getConflictType() {
        return conflictType;
    }
    
    public String getExistingResourceId() {
        return existingResourceId;
    }
    
    // Factory methods
    
    public static ConflictException duplicateRequest(String idempotencyKey) {
        return new ConflictException("DUPLICATE_REQUEST", idempotencyKey,
                "Duplicate request detected with idempotency key: " + idempotencyKey);
    }
    
    public static ConflictException duplicateBundleId(String bundleIdentifier) {
        return new ConflictException("DUPLICATE_BUNDLE", bundleIdentifier,
                "A submission with this Bundle.identifier already exists: " + bundleIdentifier);
    }
    
    public static ConflictException alreadyCancelled(String pagwId) {
        return new ConflictException("ALREADY_CANCELLED", pagwId,
                "Prior authorization has already been cancelled: " + pagwId);
    }
    
    public static ConflictException invalidStateTransition(String pagwId, String currentState, String requestedAction) {
        return new ConflictException("INVALID_STATE", pagwId,
                String.format("Cannot %s authorization in state %s", requestedAction, currentState));
    }
}
