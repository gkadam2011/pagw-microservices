package com.anthem.pagw.enricher.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.enricher.client.EligibilityClient;
import com.anthem.pagw.enricher.client.ProviderDirectoryClient;
import com.anthem.pagw.enricher.model.EnrichmentResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RequestEnricherService.
 */
@ExtendWith(MockitoExtension.class)
class RequestEnricherServiceTest {

    @Mock
    private EligibilityClient eligibilityClient;

    @Mock
    private ProviderDirectoryClient providerClient;

    private RequestEnricherService enricherService;
    private static final String TEST_PAGW_ID = "PAGW-TEST-12345";
    private static final String TEST_TENANT = "ANTHEM";

    @BeforeEach
    void setUp() {
        enricherService = new RequestEnricherService(eligibilityClient, providerClient);
    }

    @Test
    void enrich_shouldEnrichWithEligibilityAndProviderData() {
        // Given
        String claimData = """
                {
                    "patientData": {
                        "identifier": [
                            {
                                "system": "http://example.com/member-id",
                                "value": "M123456"
                            }
                        ]
                    },
                    "practitionerData": {
                        "identifier": [
                            {
                                "system": "http://hl7.org/fhir/sid/us-npi",
                                "value": "1234567890"
                            }
                        ]
                    }
                }
                """;

        PagwMessage message = createTestMessage();

        Map<String, Object> eligibilityData = Map.of(
                "memberId", "M123456",
                "status", "ACTIVE",
                "planName", "Gold PPO"
        );

        Map<String, Object> providerData = Map.of(
                "npi", "1234567890",
                "name", "Dr. Smith",
                "specialty", "Cardiology"
        );

        when(eligibilityClient.getEligibility("M123456", TEST_TENANT))
                .thenReturn(eligibilityData);
        when(providerClient.getProviderByNpi("1234567890"))
                .thenReturn(providerData);

        // When
        EnrichmentResult result = enricherService.enrich(claimData, message);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSourcesUsed()).containsExactlyInAnyOrder(
                "ELIGIBILITY_SERVICE",
                "PROVIDER_DIRECTORY"
        );

        JsonNode enrichedData = result.getEnrichedData();
        assertThat(enrichedData.has("eligibilityData")).isTrue();
        assertThat(enrichedData.has("providerDirectoryData")).isTrue();
        assertThat(enrichedData.has("enrichmentMetadata")).isTrue();

        JsonNode eligibility = enrichedData.get("eligibilityData");
        assertThat(eligibility.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode provider = enrichedData.get("providerDirectoryData");
        assertThat(provider.get("specialty").asText()).isEqualTo("Cardiology");

        JsonNode metadata = enrichedData.get("enrichmentMetadata");
        assertThat(metadata.get("pagwId").asText()).isEqualTo(TEST_PAGW_ID);
        assertThat(metadata.has("enrichedAt")).isTrue();

        verify(eligibilityClient).getEligibility("M123456", TEST_TENANT);
        verify(providerClient).getProviderByNpi("1234567890");
    }

    @Test
    void enrich_shouldHandleMissingMemberId() {
        // Given
        String claimData = """
                {
                    "patientData": {
                        "name": "John Doe"
                    }
                }
                """;

        PagwMessage message = createTestMessage();

        // When
        EnrichmentResult result = enricherService.enrich(claimData, message);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSourcesUsed()).isEmpty();
        assertThat(result.getEnrichedData().has("eligibilityData")).isFalse();
    }

    @Test
    void enrich_shouldHandleMissingNpi() {
        // Given
        String claimData = """
                {
                    "practitionerData": {
                        "name": "Dr. Smith"
                    }
                }
                """;

        PagwMessage message = createTestMessage();

        // When
        EnrichmentResult result = enricherService.enrich(claimData, message);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSourcesUsed()).isEmpty();
        assertThat(result.getEnrichedData().has("providerDirectoryData")).isFalse();
    }

    @Test
    void enrich_shouldContinueWhenEligibilityFails() {
        // Given
        String claimData = """
                {
                    "patientData": {
                        "identifier": [
                            {
                                "system": "http://example.com/member-id",
                                "value": "M123456"
                            }
                        ]
                    },
                    "practitionerData": {
                        "identifier": [
                            {
                                "system": "http://hl7.org/fhir/sid/us-npi",
                                "value": "1234567890"
                            }
                        ]
                    }
                }
                """;

        PagwMessage message = createTestMessage();

        Map<String, Object> providerData = Map.of("npi", "1234567890");

        when(eligibilityClient.getEligibility(anyString(), anyString()))
                .thenThrow(new RuntimeException("Service unavailable"));
        when(providerClient.getProviderByNpi("1234567890"))
                .thenReturn(providerData);

        // When
        EnrichmentResult result = enricherService.enrich(claimData, message);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSourcesUsed()).containsExactly("PROVIDER_DIRECTORY");
        assertThat(result.getEnrichedData().has("eligibilityData")).isFalse();
        assertThat(result.getEnrichedData().has("providerDirectoryData")).isTrue();
    }

    @Test
    void enrich_shouldExtractFirstIdentifierAsFallback() {
        // Given
        String claimData = """
                {
                    "patientData": {
                        "identifier": [
                            {
                                "system": "http://example.com/mrn",
                                "value": "MRN-999"
                            }
                        ]
                    }
                }
                """;

        PagwMessage message = createTestMessage();

        Map<String, Object> eligibilityData = Map.of("memberId", "MRN-999");
        when(eligibilityClient.getEligibility("MRN-999", TEST_TENANT))
                .thenReturn(eligibilityData);

        // When
        EnrichmentResult result = enricherService.enrich(claimData, message);

        // Then
        assertThat(result.isSuccess()).isTrue();
        verify(eligibilityClient).getEligibility("MRN-999", TEST_TENANT);
    }

    @Test
    void enrich_shouldExtractNpiFromOrganizationData() {
        // Given
        String claimData = """
                {
                    "organizationData": {
                        "identifier": [
                            {
                                "system": "urn:oid:2.16.840.1.113883.4.6",
                                "value": "9876543210"
                            }
                        ]
                    }
                }
                """;

        PagwMessage message = createTestMessage();

        Map<String, Object> providerData = Map.of("npi", "9876543210");
        when(providerClient.getProviderByNpi("9876543210"))
                .thenReturn(providerData);

        // When
        EnrichmentResult result = enricherService.enrich(claimData, message);

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSourcesUsed()).containsExactly("PROVIDER_DIRECTORY");
        verify(providerClient).getProviderByNpi("9876543210");
    }

    @Test
    void enrich_shouldHandleInvalidJson() {
        // Given
        String invalidJson = "{invalid}";
        PagwMessage message = createTestMessage();

        // When
        EnrichmentResult result = enricherService.enrich(invalidJson, message);

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotNull();
    }

    private PagwMessage createTestMessage() {
        return PagwMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .pagwId(TEST_PAGW_ID)
                .schemaVersion("1.0")
                .stage("VALIDATION_COMPLETE")
                .tenant(TEST_TENANT)
                .payloadBucket("test-bucket")
                .payloadKey("requests/" + TEST_PAGW_ID + "/validated.json")
                .metadata(null)
                .createdAt(Instant.now())
                .build();
    }
}
