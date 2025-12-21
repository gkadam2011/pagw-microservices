package com.anthem.pagw.enricher.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.enricher.client.EligibilityClient;
import com.anthem.pagw.enricher.client.ProviderDirectoryClient;
import com.anthem.pagw.enricher.model.EnrichmentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for enriching claim data with external information.
 */
@Service
public class RequestEnricherService {

    private static final Logger log = LoggerFactory.getLogger(RequestEnricherService.class);

    private final EligibilityClient eligibilityClient;
    private final ProviderDirectoryClient providerClient;

    public RequestEnricherService(
            EligibilityClient eligibilityClient,
            ProviderDirectoryClient providerClient) {
        this.eligibilityClient = eligibilityClient;
        this.providerClient = providerClient;
    }

    /**
     * Enrich claim data with external information.
     * 
     * @param claimData The validated claim data JSON
     * @param message The PAGW message context
     * @return EnrichmentResult with enriched data
     */
    public EnrichmentResult enrich(String claimData, PagwMessage message) {
        EnrichmentResult result = new EnrichmentResult();
        List<String> sourcesUsed = new ArrayList<>();
        
        try {
            ObjectNode data = (ObjectNode) JsonUtils.parseJson(claimData);
            
            // Enrich with eligibility data
            String memberId = extractMemberId(data);
            if (memberId != null) {
                try {
                    var eligibility = eligibilityClient.getEligibility(memberId, message.getTenant());
                    if (eligibility != null) {
                        data.set("eligibilityData", JsonUtils.parseJson(JsonUtils.toJson(eligibility)));
                        sourcesUsed.add("ELIGIBILITY_SERVICE");
                        log.info("Enriched with eligibility: pagwId={}, memberId={}", 
                                message.getPagwId(), memberId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch eligibility: pagwId={}, error={}", 
                            message.getPagwId(), e.getMessage());
                }
            }
            
            // Enrich with provider directory data
            String npi = extractProviderNpi(data);
            if (npi != null) {
                try {
                    var providerInfo = providerClient.getProviderByNpi(npi);
                    if (providerInfo != null) {
                        data.set("providerDirectoryData", JsonUtils.parseJson(JsonUtils.toJson(providerInfo)));
                        sourcesUsed.add("PROVIDER_DIRECTORY");
                        log.info("Enriched with provider data: pagwId={}, npi={}", 
                                message.getPagwId(), npi);
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch provider data: pagwId={}, error={}", 
                            message.getPagwId(), e.getMessage());
                }
            }
            
            // Add enrichment metadata
            ObjectNode enrichmentMeta = data.putObject("enrichmentMetadata");
            enrichmentMeta.put("enrichedAt", java.time.Instant.now().toString());
            enrichmentMeta.put("pagwId", message.getPagwId());
            enrichmentMeta.set("sourcesUsed", JsonUtils.parseJson(JsonUtils.toJson(sourcesUsed)));
            
            result.setEnrichedData(data);
            result.setSourcesUsed(sourcesUsed);
            result.setSuccess(true);
            
        } catch (Exception e) {
            log.error("Enrichment error: pagwId={}", message.getPagwId(), e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        }
        
        return result;
    }

    private String extractMemberId(JsonNode data) {
        // Try to extract member ID from patient data
        JsonNode patientData = data.path("patientData");
        if (patientData.has("identifier")) {
            JsonNode identifiers = patientData.path("identifier");
            if (identifiers.isArray()) {
                for (JsonNode id : identifiers) {
                    String system = id.path("system").asText();
                    if (system.contains("member") || system.contains("subscriber")) {
                        return id.path("value").asText();
                    }
                }
                // Fall back to first identifier
                if (!identifiers.isEmpty()) {
                    return identifiers.path(0).path("value").asText();
                }
            }
        }
        return null;
    }

    private String extractProviderNpi(JsonNode data) {
        // Try to extract NPI from practitioner or organization data
        for (String entityType : List.of("practitionerData", "organizationData")) {
            JsonNode entity = data.path(entityType);
            if (entity.has("identifier")) {
                JsonNode identifiers = entity.path("identifier");
                if (identifiers.isArray()) {
                    for (JsonNode id : identifiers) {
                        String system = id.path("system").asText();
                        if (system.contains("npi") || system.contains("2.16.840.1.113883.4.6")) {
                            return id.path("value").asText();
                        }
                    }
                }
            }
        }
        return null;
    }
}
