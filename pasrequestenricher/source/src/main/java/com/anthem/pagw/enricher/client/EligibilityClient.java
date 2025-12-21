package com.anthem.pagw.enricher.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Map;

/**
 * Client for eligibility service.
 */
@Component
public class EligibilityClient {

    private static final Logger log = LoggerFactory.getLogger(EligibilityClient.class);

    private final RestTemplate restTemplate;
    private final String eligibilityServiceUrl;

    public EligibilityClient(
            RestTemplate restTemplate,
            @Value("${pagw.external.eligibility-service-url:http://eligibility-service:8080}") String eligibilityServiceUrl) {
        this.restTemplate = restTemplate;
        this.eligibilityServiceUrl = eligibilityServiceUrl;
    }

    /**
     * Get eligibility information for a member.
     * 
     * @param memberId The member ID
     * @param tenant The tenant
     * @return Eligibility data map
     */
    public Map<String, Object> getEligibility(String memberId, String tenant) {
        try {
            String url = String.format("%s/api/v1/eligibility/%s?tenant=%s", 
                    eligibilityServiceUrl, memberId, tenant);
            
            log.debug("Fetching eligibility: memberId={}, tenant={}", memberId, tenant);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            
            return response;
            
        } catch (Exception e) {
            log.warn("Eligibility lookup failed: memberId={}, error={}", memberId, e.getMessage());
            
            // Return mock eligibility for development/testing
            return createMockEligibility(memberId, tenant);
        }
    }

    private Map<String, Object> createMockEligibility(String memberId, String tenant) {
        Map<String, Object> mock = new java.util.HashMap<>();
        mock.put("memberId", memberId);
        mock.put("tenant", tenant != null ? tenant : "default");
        mock.put("status", "ACTIVE");
        mock.put("effectiveDate", LocalDate.now().minusYears(1).toString());
        mock.put("terminationDate", LocalDate.now().plusYears(1).toString());
        mock.put("planName", "Gold PPO");
        mock.put("planType", "PPO");
        mock.put("networkStatus", "IN_NETWORK");
        mock.put("deductibleMet", true);
        mock.put("copay", 20.00);
        mock.put("coinsurance", 0.20);
        mock.put("source", "MOCK");
        return mock;
    }
}
