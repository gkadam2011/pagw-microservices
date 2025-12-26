package com.anthem.pagw.core.exception;

/**
 * Exception for resource not found errors.
 * Returns HTTP 404 Not Found.
 */
public class NotFoundException extends PagwException {
    
    private final String resourceType;
    private final String resourceId;
    
    public NotFoundException(String message) {
        this("RESOURCE", null, message);
    }
    
    public NotFoundException(String resourceType, String resourceId) {
        this(resourceType, resourceId, resourceType + " not found: " + resourceId);
    }
    
    public NotFoundException(String resourceType, String resourceId, String message) {
        super(ErrorCode.NOT_FOUND, message, null, null, null, ErrorSeverity.ERROR, 404, null);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.withContext("resourceType", resourceType);
        this.withContext("resourceId", resourceId);
    }
    
    public String getResourceType() {
        return resourceType;
    }
    
    public String getResourceId() {
        return resourceId;
    }
    
    // Factory methods
    
    public static NotFoundException authorization(String pagwId) {
        return new NotFoundException("Authorization", pagwId, 
                "Prior authorization not found: " + pagwId);
    }
    
    public static NotFoundException patient(String patientId) {
        return new NotFoundException("Patient", patientId);
    }
    
    public static NotFoundException provider(String providerId) {
        return new NotFoundException("Provider", providerId);
    }
    
    public static NotFoundException subscription(String subscriptionId) {
        return new NotFoundException("Subscription", subscriptionId);
    }
}
