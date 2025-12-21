package com.anthem.pagw.auth.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * API Gateway Lambda Authorizer response (IAM Policy).
 */
@Data
public class AuthPolicy {
    
    /**
     * The principal user identifier (client_id or user_id)
     */
    private String principalId;
    
    /**
     * IAM policy document
     */
    private PolicyDocument policyDocument;
    
    /**
     * Context to pass to downstream Lambda functions
     */
    private Map<String, Object> context;
    
    /**
     * Usage identifier key (for usage plans)
     */
    private String usageIdentifierKey;

    @Data
    public static class PolicyDocument {
        private String Version = "2012-10-17";
        private List<Statement> Statement;
    }

    @Data
    public static class Statement {
        private String Action;
        private String Effect;
        private String Resource;
    }
}
