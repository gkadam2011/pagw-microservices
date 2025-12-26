package com.anthem.pagw.core.decision;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Payer configuration for routing and API integration.
 * Maps to payer_configuration database table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayerConfiguration {

    private String payerId;
    private String payerName;
    private boolean active;
    
    // API Configuration
    private ApiConfig apiConfig;
    
    // Supported operations
    private Set<String> supportedOperations;  // SUBMIT, INQUIRY, UPDATE, CANCEL
    
    // Plan/LOB mappings
    private List<PlanMapping> planMappings;
    
    // Business rules
    private PayerRules rules;
    
    // Contact info for escalations
    private ContactInfo contactInfo;
    
    /**
     * API configuration details
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiConfig {
        private String baseUrl;
        private String submitEndpoint;
        private String inquiryEndpoint;
        private String statusEndpoint;
        
        private AuthMethod authMethod;
        private String credentialsSecretArn;
        private String oauth2TokenUrl;
        private String oauth2Scope;
        private String clientCertSecretArn;
        
        private Integer connectTimeoutMs;
        private Integer readTimeoutMs;
        private Integer maxRetries;
        private Integer retryDelayMs;
        
        private Map<String, String> defaultHeaders;
        private String x12Version;  // 005010X217, 005010X278
        
        private boolean supportsSynchronous;
        private boolean supportsAsynchronous;
        private Integer asyncPollingIntervalMs;
    }
    
    public enum AuthMethod {
        OAUTH2,
        API_KEY,
        MTLS,
        BASIC_AUTH,
        SAML
    }
    
    /**
     * Plan to payer mapping
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanMapping {
        private String planIdPattern;  // Regex pattern
        private String lineOfBusiness;
        private String delegatedPayerId;
        private boolean requiresDelegation;
    }
    
    /**
     * Payer-specific business rules
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayerRules {
        // Auto-approval rules
        private boolean supportsAutoApproval;
        private List<String> autoApprovalCptCodes;
        private List<String> autoApprovalServiceTypes;
        private Double autoApprovalMaxCost;
        private Integer autoApprovalMaxUnits;
        
        // Required fields
        private List<String> requiredFields;
        private List<String> requiredAttachmentTypes;
        
        // Timing rules
        private Integer urgentResponseSlaMinutes;
        private Integer standardResponseSlaMinutes;
        private Integer retroactiveMaxDays;
        
        // Special handling
        private boolean requiresPhoneNotification;
        private boolean requiresFaxSubmission;
        private List<String> excludedServiceTypes;
        private List<String> excludedPlaceOfService;
    }
    
    /**
     * Payer contact information
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContactInfo {
        private String providerServicesPhone;
        private String priorAuthPhone;
        private String priorAuthFax;
        private String technicalSupportEmail;
        private String escalationEmail;
    }
    
    // Payer ID constants
    public static final String PAYER_CARELON = "CARELON";
    public static final String PAYER_ELEVANCE = "ELEVANCE";
    public static final String PAYER_BCBSA = "BCBSA";
    public static final String PAYER_ANTHEM = "ANTHEM";
    public static final String PAYER_INTERSYSTEMS = "INTERSYSTEMS";
}
