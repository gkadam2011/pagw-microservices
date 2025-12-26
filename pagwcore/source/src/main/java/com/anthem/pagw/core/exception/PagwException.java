package com.anthem.pagw.core.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Base exception for all PAGW platform errors.
 * All custom exceptions should extend this class.
 * 
 * Features:
 * - Standardized error codes
 * - Severity levels matching FHIR OperationOutcome
 * - Context for tracing (pagwId, correlationId, tenant)
 * - HTTP status mapping
 */
public class PagwException extends RuntimeException {

    private final String errorCode;
    private final String pagwId;
    private final String correlationId;
    private final String tenant;
    private final ErrorSeverity severity;
    private final int httpStatus;
    private final Instant timestamp;
    private final Map<String, Object> context;

    public PagwException(String message) {
        this(ErrorCode.INTERNAL_ERROR, message, null, null, null, ErrorSeverity.ERROR, 500, null);
    }

    public PagwException(String errorCode, String message) {
        this(errorCode, message, null, null, null, ErrorSeverity.ERROR, 500, null);
    }

    public PagwException(String errorCode, String message, String pagwId) {
        this(errorCode, message, pagwId, null, null, ErrorSeverity.ERROR, 500, null);
    }

    public PagwException(String errorCode, String message, Throwable cause) {
        this(errorCode, message, null, null, null, ErrorSeverity.ERROR, 500, cause);
    }

    public PagwException(String errorCode, String message, String pagwId, Throwable cause) {
        this(errorCode, message, pagwId, null, null, ErrorSeverity.ERROR, 500, cause);
    }
    
    public PagwException(String errorCode, String message, String pagwId, String correlationId, 
                         String tenant, ErrorSeverity severity, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.pagwId = pagwId;
        this.correlationId = correlationId;
        this.tenant = tenant;
        this.severity = severity;
        this.httpStatus = httpStatus;
        this.timestamp = Instant.now();
        this.context = new HashMap<>();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getPagwId() {
        return pagwId;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public String getTenant() {
        return tenant;
    }
    
    public ErrorSeverity getSeverity() {
        return severity;
    }
    
    public int getHttpStatus() {
        return httpStatus;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public Map<String, Object> getContext() {
        return context;
    }
    
    public PagwException withContext(String key, Object value) {
        this.context.put(key, value);
        return this;
    }
    
    /**
     * Error severity levels matching FHIR OperationOutcome.
     */
    public enum ErrorSeverity {
        FATAL,      // System cannot continue
        ERROR,      // Request failed
        WARNING,    // Request succeeded but with issues
        INFORMATION // Informational message
    }
    
    /**
     * Standard error codes for PAGW platform.
     */
    public static final class ErrorCode {
        // General errors (1xxx)
        public static final String INTERNAL_ERROR = "PAGW-1000";
        public static final String INVALID_REQUEST = "PAGW-1001";
        public static final String VALIDATION_ERROR = "PAGW-1002";
        public static final String NOT_FOUND = "PAGW-1003";
        public static final String DUPLICATE = "PAGW-1004";
        public static final String TIMEOUT = "PAGW-1005";
        public static final String SERVICE_UNAVAILABLE = "PAGW-1006";
        public static final String CONFLICT = "PAGW-1007";
        
        // Authentication/Authorization (2xxx)
        public static final String UNAUTHORIZED = "PAGW-2001";
        public static final String FORBIDDEN = "PAGW-2002";
        public static final String TOKEN_EXPIRED = "PAGW-2003";
        public static final String INVALID_TOKEN = "PAGW-2004";
        public static final String PROVIDER_MISMATCH = "PAGW-2005";
        
        // FHIR/Parsing errors (3xxx)
        public static final String INVALID_FHIR = "PAGW-3001";
        public static final String MISSING_REQUIRED_FIELD = "PAGW-3002";
        public static final String INVALID_RESOURCE_TYPE = "PAGW-3003";
        public static final String INVALID_BUNDLE = "PAGW-3004";
        public static final String FHIR_VALIDATION_FAILED = "PAGW-3005";
        
        // Business validation errors (4xxx)
        public static final String BUSINESS_RULE_VIOLATION = "PAGW-4001";
        public static final String ELIGIBILITY_CHECK_FAILED = "PAGW-4002";
        public static final String COVERAGE_NOT_FOUND = "PAGW-4003";
        public static final String SERVICE_NOT_COVERED = "PAGW-4004";
        public static final String PRIOR_AUTH_REQUIRED = "PAGW-4005";
        public static final String CANNOT_CANCEL = "PAGW-4006";
        public static final String CANNOT_UPDATE = "PAGW-4007";
        public static final String ALREADY_CANCELLED = "PAGW-4008";
        
        // Processing errors (5xxx)
        public static final String PROCESSING_FAILED = "PAGW-5001";
        public static final String DOWNSTREAM_ERROR = "PAGW-5002";
        public static final String CALLBACK_FAILED = "PAGW-5003";
        public static final String ATTACHMENT_ERROR = "PAGW-5004";
        public static final String MAPPING_ERROR = "PAGW-5005";
        public static final String SYNC_TIMEOUT = "PAGW-5006";
        
        // Infrastructure errors (6xxx)
        public static final String DATABASE_ERROR = "PAGW-6001";
        public static final String S3_ERROR = "PAGW-6002";
        public static final String SQS_ERROR = "PAGW-6003";
        public static final String KMS_ERROR = "PAGW-6004";
        public static final String CACHE_ERROR = "PAGW-6005";
        public static final String NETWORK_ERROR = "PAGW-6006";
        
        private ErrorCode() {}
    }
}
