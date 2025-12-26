package com.anthem.pagw.core.decision;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for managing payer configurations.
 * In production, this would load from database/cache.
 */
@Service
public class PayerConfigurationService {

    private final Map<String, PayerConfiguration> configurations = new HashMap<>();

    public PayerConfigurationService() {
        initializeDefaultConfigurations();
    }

    /**
     * Get payer configuration by ID.
     */
    public PayerConfiguration getConfiguration(String payerId) {
        return configurations.get(payerId);
    }

    /**
     * Get all active payer configurations.
     */
    public List<PayerConfiguration> getAllActive() {
        return configurations.values().stream()
                .filter(PayerConfiguration::isActive)
                .toList();
    }

    /**
     * Initialize default payer configurations.
     * In production, load from database.
     */
    private void initializeDefaultConfigurations() {
        // Carelon (Medicare Advantage, Behavioral Health)
        configurations.put(PayerConfiguration.PAYER_CARELON, PayerConfiguration.builder()
                .payerId(PayerConfiguration.PAYER_CARELON)
                .payerName("Carelon")
                .active(true)
                .supportedOperations(Set.of("SUBMIT", "INQUIRY", "UPDATE", "CANCEL"))
                .apiConfig(PayerConfiguration.ApiConfig.builder()
                        .baseUrl("https://api.carelon.com/pas/api/v1")
                        .submitEndpoint("/submit")
                        .inquiryEndpoint("/inquiry")
                        .statusEndpoint("/status")
                        .authMethod(PayerConfiguration.AuthMethod.OAUTH2)
                        .credentialsSecretArn("arn:aws:secretsmanager:us-east-1:123456789:secret:carelon-api-creds")
                        .oauth2TokenUrl("https://auth.carelon.com/oauth2/token")
                        .oauth2Scope("pas:submit pas:inquiry")
                        .connectTimeoutMs(5000)
                        .readTimeoutMs(30000)
                        .maxRetries(3)
                        .retryDelayMs(1000)
                        .x12Version("005010X217")
                        .supportsSynchronous(true)
                        .supportsAsynchronous(true)
                        .asyncPollingIntervalMs(60000)
                        .defaultHeaders(Map.of(
                                "X-Carelon-Partner-Id", "PAGW",
                                "Accept", "application/fhir+json"
                        ))
                        .build())
                .rules(PayerConfiguration.PayerRules.builder()
                        .supportsAutoApproval(true)
                        .autoApprovalCptCodes(List.of("99213", "99214", "99215")) // Office visits
                        .autoApprovalServiceTypes(List.of("1", "2")) // Medical Care, Surgical
                        .autoApprovalMaxCost(5000.0)
                        .autoApprovalMaxUnits(10)
                        .urgentResponseSlaMinutes(15)
                        .standardResponseSlaMinutes(1440)
                        .retroactiveMaxDays(30)
                        .requiredFields(List.of("providerNpi", "memberId", "serviceTypeCode", "cptCodes"))
                        .build())
                .contactInfo(PayerConfiguration.ContactInfo.builder()
                        .priorAuthPhone("1-800-CARELON")
                        .technicalSupportEmail("pas-support@carelon.com")
                        .build())
                .build());

        // Elevance Health (Commercial, Medicaid)
        configurations.put(PayerConfiguration.PAYER_ELEVANCE, PayerConfiguration.builder()
                .payerId(PayerConfiguration.PAYER_ELEVANCE)
                .payerName("Elevance Health")
                .active(true)
                .supportedOperations(Set.of("SUBMIT", "INQUIRY", "UPDATE", "CANCEL"))
                .apiConfig(PayerConfiguration.ApiConfig.builder()
                        .baseUrl("https://api.elevancehealth.com/priorauth/v2")
                        .submitEndpoint("/Claim/$submit")
                        .inquiryEndpoint("/Claim/$inquiry")
                        .statusEndpoint("/ClaimResponse")
                        .authMethod(PayerConfiguration.AuthMethod.OAUTH2)
                        .credentialsSecretArn("arn:aws:secretsmanager:us-east-1:123456789:secret:elevance-api-creds")
                        .oauth2TokenUrl("https://auth.elevancehealth.com/connect/token")
                        .oauth2Scope("priorauth.submit priorauth.inquiry")
                        .connectTimeoutMs(5000)
                        .readTimeoutMs(30000)
                        .maxRetries(3)
                        .retryDelayMs(1000)
                        .x12Version("005010X217")
                        .supportsSynchronous(true)
                        .supportsAsynchronous(true)
                        .asyncPollingIntervalMs(30000)
                        .defaultHeaders(Map.of(
                                "Content-Type", "application/fhir+json",
                                "Accept", "application/fhir+json"
                        ))
                        .build())
                .rules(PayerConfiguration.PayerRules.builder()
                        .supportsAutoApproval(true)
                        .autoApprovalCptCodes(List.of("99201", "99202", "99203", "99211", "99212"))
                        .autoApprovalMaxCost(2500.0)
                        .urgentResponseSlaMinutes(30)
                        .standardResponseSlaMinutes(2880)
                        .retroactiveMaxDays(14)
                        .requiredFields(List.of("providerNpi", "memberId", "diagnosisCode"))
                        .build())
                .contactInfo(PayerConfiguration.ContactInfo.builder()
                        .priorAuthPhone("1-800-ELEVANCE")
                        .priorAuthFax("1-800-555-1234")
                        .technicalSupportEmail("api-support@elevancehealth.com")
                        .build())
                .build());

        // BCBSA (Blue Cross Blue Shield Association)
        configurations.put(PayerConfiguration.PAYER_BCBSA, PayerConfiguration.builder()
                .payerId(PayerConfiguration.PAYER_BCBSA)
                .payerName("Blue Cross Blue Shield Association")
                .active(true)
                .supportedOperations(Set.of("SUBMIT", "INQUIRY"))
                .apiConfig(PayerConfiguration.ApiConfig.builder()
                        .baseUrl("https://api.bcbsa.com/priorauth/v1")
                        .submitEndpoint("/submit")
                        .inquiryEndpoint("/inquiry")
                        .statusEndpoint("/status")
                        .authMethod(PayerConfiguration.AuthMethod.MTLS)
                        .credentialsSecretArn("arn:aws:secretsmanager:us-east-1:123456789:secret:bcbsa-api-creds")
                        .clientCertSecretArn("arn:aws:secretsmanager:us-east-1:123456789:secret:bcbsa-mtls-cert")
                        .connectTimeoutMs(10000)
                        .readTimeoutMs(60000)
                        .maxRetries(2)
                        .retryDelayMs(2000)
                        .x12Version("005010X278")
                        .supportsSynchronous(false)
                        .supportsAsynchronous(true)
                        .asyncPollingIntervalMs(120000)
                        .build())
                .rules(PayerConfiguration.PayerRules.builder()
                        .supportsAutoApproval(false)
                        .urgentResponseSlaMinutes(60)
                        .standardResponseSlaMinutes(4320) // 72 hours
                        .retroactiveMaxDays(7)
                        .requiredAttachmentTypes(List.of("CLINICAL_NOTES"))
                        .build())
                .build());

        // InterSystems (HealthShare)
        configurations.put(PayerConfiguration.PAYER_INTERSYSTEMS, PayerConfiguration.builder()
                .payerId(PayerConfiguration.PAYER_INTERSYSTEMS)
                .payerName("InterSystems HealthShare")
                .active(true)
                .supportedOperations(Set.of("SUBMIT", "INQUIRY", "UPDATE", "CANCEL"))
                .apiConfig(PayerConfiguration.ApiConfig.builder()
                        .baseUrl("https://healthshare.intersystems.com/csp/healthshare/fhirauth/r4")
                        .submitEndpoint("/Claim/$submit")
                        .inquiryEndpoint("/Claim/$inquiry")
                        .statusEndpoint("/ClaimResponse")
                        .authMethod(PayerConfiguration.AuthMethod.OAUTH2)
                        .credentialsSecretArn("arn:aws:secretsmanager:us-east-1:123456789:secret:intersystems-creds")
                        .oauth2TokenUrl("https://healthshare.intersystems.com/oauth2/token")
                        .oauth2Scope("user/Claim.write user/ClaimResponse.read")
                        .connectTimeoutMs(5000)
                        .readTimeoutMs(15000) // 15 second sync response
                        .maxRetries(1)
                        .x12Version("005010X217")
                        .supportsSynchronous(true)
                        .supportsAsynchronous(true)
                        .defaultHeaders(Map.of(
                                "Content-Type", "application/fhir+json",
                                "Accept", "application/fhir+json",
                                "Prefer", "respond-async"
                        ))
                        .build())
                .rules(PayerConfiguration.PayerRules.builder()
                        .supportsAutoApproval(false)
                        .urgentResponseSlaMinutes(15)
                        .standardResponseSlaMinutes(15)
                        .build())
                .build());

        // Default configuration (fallback)
        configurations.put("DEFAULT", PayerConfiguration.builder()
                .payerId("DEFAULT")
                .payerName("Default Payer")
                .active(true)
                .supportedOperations(Set.of("SUBMIT", "INQUIRY"))
                .apiConfig(PayerConfiguration.ApiConfig.builder()
                        .baseUrl("https://api.default-payer.com/pas/api/v1")
                        .submitEndpoint("/submit")
                        .inquiryEndpoint("/inquiry")
                        .authMethod(PayerConfiguration.AuthMethod.API_KEY)
                        .credentialsSecretArn("arn:aws:secretsmanager:us-east-1:123456789:secret:default-api-key")
                        .connectTimeoutMs(5000)
                        .readTimeoutMs(30000)
                        .maxRetries(3)
                        .x12Version("005010X217")
                        .supportsSynchronous(true)
                        .supportsAsynchronous(false)
                        .build())
                .rules(PayerConfiguration.PayerRules.builder()
                        .supportsAutoApproval(false)
                        .urgentResponseSlaMinutes(60)
                        .standardResponseSlaMinutes(2880)
                        .build())
                .build());
    }

    /**
     * Refresh configuration from database.
     * Called periodically or on config change events.
     */
    public void refreshConfigurations() {
        // TODO: Load from database
        // List<PayerConfigurationEntity> entities = payerConfigRepository.findAllActive();
        // entities.forEach(entity -> configurations.put(entity.getPayerId(), mapToConfig(entity)));
    }
}
