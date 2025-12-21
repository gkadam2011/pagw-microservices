package com.anthem.pagw.auth.model;

import lombok.Data;
import java.util.Map;

/**
 * API Gateway Lambda Authorizer request.
 */
@Data
public class AuthRequest {
    
    /**
     * The type of authorizer: TOKEN or REQUEST
     */
    private String type;
    
    /**
     * The authorization token (for TOKEN authorizer)
     */
    private String authorizationToken;
    
    /**
     * The method ARN being invoked
     * Format: arn:aws:execute-api:region:account-id:api-id/stage/method/resource-path
     */
    private String methodArn;
    
    /**
     * Request headers (for REQUEST authorizer)
     */
    private Map<String, String> headers;
    
    /**
     * Query string parameters
     */
    private Map<String, String> queryStringParameters;
    
    /**
     * Path parameters
     */
    private Map<String, String> pathParameters;
    
    /**
     * Stage variables
     */
    private Map<String, String> stageVariables;
    
    /**
     * Request context
     */
    private Map<String, Object> requestContext;
}
