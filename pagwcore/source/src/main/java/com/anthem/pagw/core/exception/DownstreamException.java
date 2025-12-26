package com.anthem.pagw.core.exception;

/**
 * Exception thrown when a downstream service call fails.
 * Returns HTTP 502 Bad Gateway or 504 Gateway Timeout.
 */
public class DownstreamException extends PagwException {

    private final String serviceName;
    private final int statusCode;
    private final String responseBody;
    private final long latencyMs;

    public DownstreamException(String serviceName, String message) {
        this(serviceName, 0, message, null, 0, null);
    }

    public DownstreamException(String serviceName, int statusCode, String message) {
        this(serviceName, statusCode, message, null, 0, null);
    }

    public DownstreamException(String serviceName, String message, Throwable cause) {
        this(serviceName, 0, message, null, 0, cause);
    }
    
    public DownstreamException(String serviceName, int statusCode, String message, 
                               String responseBody, long latencyMs, Throwable cause) {
        super(ErrorCode.DOWNSTREAM_ERROR, message, null, null, null, 
                ErrorSeverity.ERROR, mapToHttpStatus(statusCode), cause);
        this.serviceName = serviceName;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.latencyMs = latencyMs;
        
        // Add context
        this.withContext("serviceName", serviceName);
        this.withContext("downstreamStatusCode", statusCode);
        this.withContext("latencyMs", latencyMs);
    }
    
    private static int mapToHttpStatus(int downstreamStatus) {
        if (downstreamStatus == 0) {
            return 502; // Bad Gateway - couldn't connect
        } else if (downstreamStatus >= 500) {
            return 502; // Bad Gateway - downstream error
        } else if (downstreamStatus == 408 || downstreamStatus == 504) {
            return 504; // Gateway Timeout
        }
        return 502;
    }

    public String getServiceName() {
        return serviceName;
    }

    public int getStatusCode() {
        return statusCode;
    }
    
    public String getResponseBody() {
        return responseBody;
    }
    
    public long getLatencyMs() {
        return latencyMs;
    }
    
    // Factory methods
    
    public static DownstreamException timeout(String serviceName, long latencyMs) {
        return new DownstreamException(
                serviceName, 504, 
                "Request to " + serviceName + " timed out after " + latencyMs + "ms",
                null, latencyMs, null
        );
    }
    
    public static DownstreamException connectionFailed(String serviceName, Throwable cause) {
        return new DownstreamException(
                serviceName, 0,
                "Failed to connect to " + serviceName + ": " + cause.getMessage(),
                null, 0, cause
        );
    }
}
