package com.anthem.pagw.core.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Output from the Decision Engine.
 * Contains routing decision, auto-approval status, and processing instructions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecisionResponse {

    // Decision outcome
    private Decision decision;
    private String decisionReason;
    private List<String> appliedRules;
    
    // Routing information
    private RoutingInfo routing;
    
    // Priority/urgency
    private Priority priority;
    private Integer slaMinutes;  // Expected response time
    
    // Auto-approval details (if applicable)
    private AutoApprovalInfo autoApproval;
    
    // Processing instructions
    private ProcessingInstructions instructions;
    
    // Confidence and explanation
    private Double confidenceScore;  // 0.0-1.0
    private List<String> explanations;
    
    /**
     * Decision types
     */
    public enum Decision {
        AUTO_APPROVE,      // Bypass payer, approve immediately
        ROUTE_TO_PAYER,    // Standard payer submission
        REJECT,            // Reject without sending to payer
        MANUAL_REVIEW,     // Flag for human review
        PENDING_INFO       // Missing required information
    }
    
    /**
     * Processing priority
     */
    public enum Priority {
        URGENT(15),        // 15 minute SLA
        HIGH(60),          // 1 hour SLA
        STANDARD(1440),    // 24 hour SLA
        LOW(4320);         // 72 hour SLA
        
        private final int slaMinutes;
        
        Priority(int slaMinutes) {
            this.slaMinutes = slaMinutes;
        }
        
        public int getSlaMinutes() {
            return slaMinutes;
        }
    }
    
    /**
     * Routing information
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingInfo {
        private String payerId;
        private String payerName;
        private String apiEndpoint;
        private String authMethod;  // OAUTH2, API_KEY, MTLS
        private String credentialsSecretArn;
        private Map<String, String> payerHeaders;
        private Integer timeoutSeconds;
        private Integer maxRetries;
    }
    
    /**
     * Auto-approval details
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutoApprovalInfo {
        private boolean eligible;
        private String eligibilityReason;
        private String approvalCode;
        private Integer authorizedUnits;
        private Integer validityDays;
        private List<String> matchedRules;
    }
    
    /**
     * Processing instructions for downstream services
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingInstructions {
        private boolean requiresAttachments;
        private List<String> requiredAttachmentTypes;
        private boolean requiresAdditionalEnrichment;
        private List<String> enrichmentSources;
        private boolean skipValidation;
        private boolean skipMapping;
        private String preferredX12Version;  // 005010X217, 005010X278
        private Map<String, Object> payerSpecificConfig;
    }
    
    /**
     * Factory for auto-approval decision
     */
    public static DecisionResponse autoApprove(String reason, List<String> rules) {
        return DecisionResponse.builder()
                .decision(Decision.AUTO_APPROVE)
                .decisionReason(reason)
                .appliedRules(rules)
                .priority(Priority.STANDARD)
                .autoApproval(AutoApprovalInfo.builder()
                        .eligible(true)
                        .eligibilityReason(reason)
                        .validityDays(90)
                        .matchedRules(rules)
                        .build())
                .confidenceScore(1.0)
                .build();
    }
    
    /**
     * Factory for payer routing decision
     */
    public static DecisionResponse routeToPayer(RoutingInfo routing, Priority priority) {
        return DecisionResponse.builder()
                .decision(Decision.ROUTE_TO_PAYER)
                .decisionReason("Standard payer submission required")
                .routing(routing)
                .priority(priority)
                .slaMinutes(priority.getSlaMinutes())
                .build();
    }
    
    /**
     * Factory for rejection decision
     */
    public static DecisionResponse reject(String reason, List<String> explanations) {
        return DecisionResponse.builder()
                .decision(Decision.REJECT)
                .decisionReason(reason)
                .explanations(explanations)
                .confidenceScore(1.0)
                .build();
    }
}
