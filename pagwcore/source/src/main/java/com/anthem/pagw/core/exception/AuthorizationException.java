package com.anthem.pagw.core.exception;

/**
 * Exception for authentication/authorization errors.
 * Returns HTTP 401 Unauthorized or 403 Forbidden.
 */
public class AuthorizationException extends PagwException {
    
    private final AuthType authType;
    
    public enum AuthType {
        UNAUTHORIZED,    // 401 - no/invalid credentials
        FORBIDDEN,       // 403 - valid credentials but not allowed
        TOKEN_EXPIRED,   // 401 - token has expired
        PROVIDER_MISMATCH // 403 - provider in request doesn't match token
    }
    
    public AuthorizationException(String message) {
        this(AuthType.UNAUTHORIZED, message);
    }
    
    public AuthorizationException(AuthType authType, String message) {
        super(mapToErrorCode(authType), message, null, null, null, 
                ErrorSeverity.ERROR, mapToHttpStatus(authType), null);
        this.authType = authType;
    }
    
    public AuthorizationException(AuthType authType, String message, String pagwId) {
        super(mapToErrorCode(authType), message, pagwId, null, null, 
                ErrorSeverity.ERROR, mapToHttpStatus(authType), null);
        this.authType = authType;
    }
    
    private static String mapToErrorCode(AuthType type) {
        return switch (type) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case TOKEN_EXPIRED -> ErrorCode.TOKEN_EXPIRED;
            case PROVIDER_MISMATCH -> ErrorCode.PROVIDER_MISMATCH;
        };
    }
    
    private static int mapToHttpStatus(AuthType type) {
        return switch (type) {
            case UNAUTHORIZED, TOKEN_EXPIRED -> 401;
            case FORBIDDEN, PROVIDER_MISMATCH -> 403;
        };
    }
    
    public AuthType getAuthType() {
        return authType;
    }
    
    // Factory methods
    
    public static AuthorizationException unauthorized(String reason) {
        return new AuthorizationException(AuthType.UNAUTHORIZED, 
                "Authentication required: " + reason);
    }
    
    public static AuthorizationException forbidden(String reason) {
        return new AuthorizationException(AuthType.FORBIDDEN,
                "Access denied: " + reason);
    }
    
    public static AuthorizationException tokenExpired() {
        return new AuthorizationException(AuthType.TOKEN_EXPIRED,
                "Authentication token has expired");
    }
    
    public static AuthorizationException providerMismatch(String claimProvider, String tokenProvider) {
        return new AuthorizationException(AuthType.PROVIDER_MISMATCH,
                String.format("Provider mismatch: Claim.provider (%s) does not match authenticated provider (%s)",
                        claimProvider, tokenProvider));
    }
}
