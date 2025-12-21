package com.anthem.pagw.enricher.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Client for provider directory service.
 */
@Component
public class ProviderDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderDirectoryClient.class);

    private final RestTemplate restTemplate;
    private final String providerDirectoryUrl;

    public ProviderDirectoryClient(
            RestTemplate restTemplate,
            @Value("${pagw.external.provider-directory-url:http://provider-directory:8080}") String providerDirectoryUrl) {
        this.restTemplate = restTemplate;
        this.providerDirectoryUrl = providerDirectoryUrl;
    }

    /**
     * Get provider information by NPI.
     * 
     * @param npi The National Provider Identifier
     * @return Provider data map
     */
    public Map<String, Object> getProviderByNpi(String npi) {
        try {
            String url = String.format("%s/api/v1/providers/npi/%s", providerDirectoryUrl, npi);
            
            log.debug("Fetching provider: npi={}", npi);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            
            return response;
            
        } catch (Exception e) {
            log.warn("Provider lookup failed: npi={}, error={}", npi, e.getMessage());
            
            // Return mock provider for development/testing
            return createMockProvider(npi);
        }
    }

    private Map<String, Object> createMockProvider(String npi) {
        Map<String, Object> mock = new java.util.HashMap<>();
        mock.put("npi", npi);
        mock.put("name", "Mock Provider");
        mock.put("type", "INDIVIDUAL");
        mock.put("specialty", "General Practice");
        mock.put("networkStatus", "IN_NETWORK");
        mock.put("tier", "TIER_1");
        mock.put("address", Map.of(
                "line", "123 Medical Center Dr",
                "city", "Indianapolis",
                "state", "IN",
                "postalCode", "46204"
        ));
        mock.put("phone", "317-555-0100");
        mock.put("acceptingPatients", true);
        mock.put("credentials", List.of("MD", "FACP"));
        mock.put("languages", List.of("English", "Spanish"));
        mock.put("source", "MOCK");
        return mock;
    }
}
