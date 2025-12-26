package com.anthem.pagw.core.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Decision Engine for Prior Authorization routing and auto-approval.
 * 
 * Responsibilities:
 * - Determine if request qualifies for auto-approval
 * - Route requests to appropriate payer/delegate
 * - Calculate priority and SLA based on urgency
 * - Apply gold-card provider rules
 * - Apply payer-specific business rules
 */
@Service
public class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);
    
    private final PayerConfigurationService payerConfigService;
    private final List<DecisionRule> decisionRules;

    public DecisionEngine(PayerConfigurationService payerConfigService, 
                          List<DecisionRule> decisionRules) {
        this.payerConfigService = payerConfigService;
        this.decisionRules = decisionRules != null ? decisionRules : new ArrayList<>();
    }

    /**
     * Make routing and approval decision for a PA request.
     */
    public DecisionResponse evaluate(DecisionRequest request) {
        log.info("Evaluating decision for pagwId={}, provider={}, serviceType={}", 
                request.getPagwId(), request.getProviderNpi(), request.getServiceTypeCode());
        
        List<String> appliedRules = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        
        // 1. Determine priority/urgency
        DecisionResponse.Priority priority = determinePriority(request, explanations);
        
        // 2. Check for rejection conditions
        Optional<DecisionResponse> rejection = checkRejectionRules(request, explanations);
        if (rejection.isPresent()) {
            log.info("Request {} rejected: {}", request.getPagwId(), rejection.get().getDecisionReason());
            return rejection.get();
        }
        
        // 3. Check for auto-approval eligibility
        Optional<DecisionResponse> autoApproval = checkAutoApproval(request, appliedRules, explanations);
        if (autoApproval.isPresent()) {
            log.info("Request {} auto-approved: {}", request.getPagwId(), autoApproval.get().getDecisionReason());
            return autoApproval.get();
        }
        
        // 4. Determine payer routing
        DecisionResponse.RoutingInfo routing = determineRouting(request, explanations);
        
        // 5. Build processing instructions
        DecisionResponse.ProcessingInstructions instructions = buildInstructions(request, routing);
        
        // 6. Apply custom decision rules
        for (DecisionRule rule : decisionRules) {
            if (rule.isApplicable(request)) {
                var ruleResult = rule.evaluate(request);
                if (ruleResult != null) {
                    appliedRules.add(rule.getName());
                    // Rule can override decision
                    if (ruleResult.getDecision() != null) {
                        return ruleResult;
                    }
                }
            }
        }
        
        return DecisionResponse.builder()
                .decision(DecisionResponse.Decision.ROUTE_TO_PAYER)
                .decisionReason("Standard payer submission")
                .routing(routing)
                .priority(priority)
                .slaMinutes(priority.getSlaMinutes())
                .instructions(instructions)
                .appliedRules(appliedRules)
                .explanations(explanations)
                .confidenceScore(0.95)
                .build();
    }

    /**
     * Determine processing priority based on urgency factors.
     */
    private DecisionResponse.Priority determinePriority(DecisionRequest request, 
            List<String> explanations) {
        
        // Explicit urgent flag
        if (request.isUrgent()) {
            explanations.add("Urgent flag set on request");
            return DecisionResponse.Priority.URGENT;
        }
        
        // Urgent service types (Emergency, Urgent Care)
        if (request.isUrgentByServiceType()) {
            explanations.add("Urgent service type: " + request.getServiceTypeCode());
            return DecisionResponse.Priority.URGENT;
        }
        
        // Inpatient services get high priority
        if (isInpatientService(request.getPlaceOfServiceCode())) {
            explanations.add("Inpatient service - high priority");
            return DecisionResponse.Priority.HIGH;
        }
        
        // Retroactive requests
        if (request.isRetroactive()) {
            explanations.add("Retroactive request - standard priority");
            return DecisionResponse.Priority.STANDARD;
        }
        
        // High-cost procedures
        if (request.getEstimatedCost() != null && request.getEstimatedCost() > 50000) {
            explanations.add("High-cost procedure ($" + request.getEstimatedCost() + ")");
            return DecisionResponse.Priority.HIGH;
        }
        
        return DecisionResponse.Priority.STANDARD;
    }

    /**
     * Check for conditions that should reject the request immediately.
     */
    private Optional<DecisionResponse> checkRejectionRules(DecisionRequest request, 
            List<String> explanations) {
        
        List<String> rejectionReasons = new ArrayList<>();
        
        // Missing required identifiers
        if (request.getProviderNpi() == null || request.getProviderNpi().isBlank()) {
            rejectionReasons.add("Provider NPI is required");
        }
        
        if (request.getMemberId() == null || request.getMemberId().isBlank()) {
            rejectionReasons.add("Member ID is required");
        }
        
        // Invalid service date
        if (request.getServiceStartDate() != null && request.getServiceEndDate() != null) {
            if (request.getServiceEndDate().isBefore(request.getServiceStartDate())) {
                rejectionReasons.add("Service end date cannot be before start date");
            }
        }
        
        // Check payer supports requested operation
        PayerConfiguration payerConfig = payerConfigService.getConfiguration(
                request.getPreferredPayerId() != null ? request.getPreferredPayerId() : "DEFAULT");
        if (payerConfig != null && payerConfig.getSupportedOperations() != null) {
            if (!payerConfig.getSupportedOperations().contains(request.getRequestType())) {
                rejectionReasons.add("Payer does not support operation: " + request.getRequestType());
            }
        }
        
        // Check for excluded service types
        if (payerConfig != null && payerConfig.getRules() != null 
                && payerConfig.getRules().getExcludedServiceTypes() != null) {
            if (payerConfig.getRules().getExcludedServiceTypes().contains(request.getServiceTypeCode())) {
                rejectionReasons.add("Service type " + request.getServiceTypeCode() + " is excluded by payer");
            }
        }
        
        if (!rejectionReasons.isEmpty()) {
            return Optional.of(DecisionResponse.reject(
                    "Request does not meet minimum requirements",
                    rejectionReasons
            ));
        }
        
        return Optional.empty();
    }

    /**
     * Check if request qualifies for auto-approval.
     */
    private Optional<DecisionResponse> checkAutoApproval(DecisionRequest request, 
            List<String> appliedRules, List<String> explanations) {
        
        // Gold-card provider auto-approval
        if (request.isGoldCardProvider()) {
            if (request.getProviderApprovalRate() != null && request.getProviderApprovalRate() >= 0.95) {
                appliedRules.add("GOLD_CARD_PROVIDER");
                explanations.add("Gold-card provider with 95%+ approval rate");
                return Optional.of(DecisionResponse.autoApprove(
                        "Gold-card provider auto-approval",
                        List.of("GOLD_CARD_PROVIDER")
                ));
            }
        }
        
        // Check payer-specific auto-approval rules
        PayerConfiguration payerConfig = payerConfigService.getConfiguration(
                request.getPreferredPayerId() != null ? request.getPreferredPayerId() : "DEFAULT");
        
        if (payerConfig != null && payerConfig.getRules() != null) {
            PayerConfiguration.PayerRules rules = payerConfig.getRules();
            
            if (rules.isSupportsAutoApproval()) {
                // CPT code whitelist
                if (rules.getAutoApprovalCptCodes() != null && request.getCptCodes() != null) {
                    boolean allCptsApproved = request.getCptCodes().stream()
                            .allMatch(cpt -> rules.getAutoApprovalCptCodes().contains(cpt));
                    
                    if (allCptsApproved) {
                        // Check cost threshold
                        if (rules.getAutoApprovalMaxCost() == null 
                                || request.getEstimatedCost() == null
                                || request.getEstimatedCost() <= rules.getAutoApprovalMaxCost()) {
                            
                            appliedRules.add("CPT_WHITELIST_AUTO_APPROVAL");
                            explanations.add("All CPT codes on auto-approval list");
                            return Optional.of(DecisionResponse.autoApprove(
                                    "CPT codes qualify for auto-approval",
                                    List.of("CPT_WHITELIST_AUTO_APPROVAL")
                            ));
                        }
                    }
                }
                
                // Service type whitelist
                if (rules.getAutoApprovalServiceTypes() != null 
                        && rules.getAutoApprovalServiceTypes().contains(request.getServiceTypeCode())) {
                    appliedRules.add("SERVICE_TYPE_AUTO_APPROVAL");
                    explanations.add("Service type qualifies for auto-approval");
                    return Optional.of(DecisionResponse.autoApprove(
                            "Service type qualifies for auto-approval",
                            List.of("SERVICE_TYPE_AUTO_APPROVAL")
                    ));
                }
            }
        }
        
        return Optional.empty();
    }

    /**
     * Determine which payer/endpoint to route the request to.
     */
    private DecisionResponse.RoutingInfo determineRouting(DecisionRequest request, 
            List<String> explanations) {
        
        String targetPayerId = request.getPreferredPayerId();
        
        // Check for delegation
        if (request.getDelegatedPayerId() != null && !request.getDelegatedPayerId().isBlank()) {
            targetPayerId = request.getDelegatedPayerId();
            explanations.add("Routing to delegated payer: " + targetPayerId);
        }
        
        // Determine payer based on plan/LOB if not specified
        if (targetPayerId == null || targetPayerId.isBlank()) {
            targetPayerId = determinePayerFromPlan(request);
            explanations.add("Determined payer from plan: " + targetPayerId);
        }
        
        PayerConfiguration payerConfig = payerConfigService.getConfiguration(targetPayerId);
        if (payerConfig == null) {
            payerConfig = payerConfigService.getConfiguration("DEFAULT");
            explanations.add("Using default payer configuration");
        }
        
        PayerConfiguration.ApiConfig apiConfig = payerConfig.getApiConfig();
        
        return DecisionResponse.RoutingInfo.builder()
                .payerId(targetPayerId)
                .payerName(payerConfig.getPayerName())
                .apiEndpoint(apiConfig.getBaseUrl() + apiConfig.getSubmitEndpoint())
                .authMethod(apiConfig.getAuthMethod().name())
                .credentialsSecretArn(apiConfig.getCredentialsSecretArn())
                .timeoutSeconds(apiConfig.getReadTimeoutMs() / 1000)
                .maxRetries(apiConfig.getMaxRetries())
                .payerHeaders(apiConfig.getDefaultHeaders())
                .build();
    }

    /**
     * Determine payer based on plan ID and line of business.
     */
    private String determinePayerFromPlan(DecisionRequest request) {
        String planId = request.getPlanId();
        String lob = request.getLineOfBusiness();
        
        // Default routing based on LOB
        if ("MEDICARE".equalsIgnoreCase(lob)) {
            return PayerConfiguration.PAYER_CARELON;
        } else if ("MEDICAID".equalsIgnoreCase(lob)) {
            return PayerConfiguration.PAYER_ELEVANCE;
        } else if (planId != null && planId.startsWith("BCBS")) {
            return PayerConfiguration.PAYER_BCBSA;
        }
        
        // Default to Anthem/Elevance
        return PayerConfiguration.PAYER_ANTHEM;
    }

    /**
     * Build processing instructions for downstream services.
     */
    private DecisionResponse.ProcessingInstructions buildInstructions(
            DecisionRequest request, DecisionResponse.RoutingInfo routing) {
        
        PayerConfiguration payerConfig = payerConfigService.getConfiguration(routing.getPayerId());
        
        boolean requiresAttachments = false;
        List<String> requiredAttachmentTypes = new ArrayList<>();
        
        // Certain service types require attachments
        if (Set.of("3", "4", "5").contains(request.getServiceTypeCode())) { // Consultation, Diagnostic
            requiresAttachments = true;
            requiredAttachmentTypes.add("CLINICAL_NOTES");
        }
        
        // High-cost procedures require clinical documentation
        if (request.getEstimatedCost() != null && request.getEstimatedCost() > 10000) {
            requiresAttachments = true;
            requiredAttachmentTypes.add("MEDICAL_RECORDS");
        }
        
        // Payer-specific requirements
        if (payerConfig != null && payerConfig.getRules() != null) {
            List<String> payerRequirements = payerConfig.getRules().getRequiredAttachmentTypes();
            if (payerRequirements != null) {
                requiredAttachmentTypes.addAll(payerRequirements);
                if (!payerRequirements.isEmpty()) {
                    requiresAttachments = true;
                }
            }
        }
        
        String x12Version = "005010X217"; // Default
        if (payerConfig != null && payerConfig.getApiConfig() != null) {
            x12Version = payerConfig.getApiConfig().getX12Version();
        }
        
        return DecisionResponse.ProcessingInstructions.builder()
                .requiresAttachments(requiresAttachments)
                .requiredAttachmentTypes(requiredAttachmentTypes)
                .requiresAdditionalEnrichment(false)
                .skipValidation(false)
                .skipMapping(false)
                .preferredX12Version(x12Version)
                .build();
    }

    /**
     * Check if place of service indicates inpatient.
     */
    private boolean isInpatientService(String placeOfService) {
        if (placeOfService == null) return false;
        return Set.of("21", "51", "61").contains(placeOfService); // Inpatient, Psych, Comprehensive
    }
}
