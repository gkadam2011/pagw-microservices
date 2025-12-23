package com.anthem.pagw.converter.service;

import com.anthem.pagw.converter.model.ConversionResult;
import com.anthem.pagw.converter.model.ConvertedPayload;
import com.anthem.pagw.core.model.PagwMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RequestConverterService.
 */
class RequestConverterServiceTest {

    private RequestConverterService service;

    @BeforeEach
    void setUp() {
        service = new RequestConverterService();
    }

    @Test
    void shouldConvertProfessionalClaim() {
        // Given
        String enrichedData = """
                {
                    "claimId": "CLM-001",
                    "claimType": "professional",
                    "use": "claim",
                    "priority": "normal",
                    "created": "2024-01-15T10:00:00Z",
                    "totalValue": 250.00,
                    "totalCurrency": "USD",
                    "patientData": {
                        "id": "P123",
                        "memberId": "M456",
                        "firstName": "John",
                        "lastName": "Doe",
                        "dateOfBirth": "1980-05-15",
                        "gender": "male"
                    },
                    "providerData": {
                        "id": "PROV-001",
                        "npi": "1234567890",
                        "name": "Dr. Smith",
                        "specialty": "Cardiology",
                        "networkStatus": "IN_NETWORK",
                        "tier": "TIER_1"
                    },
                    "insuranceData": {
                        "sequence": 1,
                        "primary": true,
                        "coverageReference": "COV-123",
                        "planName": "PPO Gold",
                        "planType": "PPO",
                        "groupNumber": "GRP-789"
                    },
                    "lineItems": [
                        {
                            "sequence": 1,
                            "procedureCode": "99213",
                            "description": "Office Visit",
                            "quantity": 1,
                            "unitPrice": 150.00,
                            "totalPrice": 150.00
                        }
                    ]
                }
                """;

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-12345")
                .tenant("ANTHEM")
                .schemaVersion("1.0")
                .stage("REQUEST_CONVERTER")
                .payloadBucket("pagw-request-dev")
                .payloadKey("enriched/PAGW-12345.json")
                .metadata(Map.of("priority", "normal"))
                .createdAt(Instant.now())
                .build();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertTrue(result.isSuccess());
        assertEquals("CLAIMS_PRO", result.getTargetSystem());

        ConvertedPayload payload = result.getConvertedPayload();
        assertNotNull(payload);
        assertEquals("PAGW-12345", payload.getPagwId());
        assertEquals("ANTHEM", payload.getTenant());
        assertEquals("CLM-001", payload.getSourceClaimId());
        assertEquals("837P", payload.getClaimType());
        assertEquals("CLAIMS_PRO", payload.getTargetSystem());
        assertNotNull(payload.getPatient());
        assertEquals("P123", payload.getPatient().getId());
        assertNotNull(payload.getProvider());
        assertEquals("1234567890", payload.getProvider().getNpi());
    }

    @Test
    void shouldConvertInstitutionalClaim() {
        // Given
        String enrichedData = """
                {
                    "claimId": "CLM-002",
                    "claimType": "institutional",
                    "use": "claim",
                    "priority": "urgent",
                    "created": "2024-01-15T10:00:00Z",
                    "totalValue": 5000.00,
                    "totalCurrency": "USD",
                    "patientData": {
                        "id": "P456",
                        "memberId": "M789",
                        "firstName": "Jane",
                        "lastName": "Smith",
                        "dateOfBirth": "1975-03-20",
                        "gender": "female"
                    },
                    "providerData": {
                        "id": "HOSP-001",
                        "npi": "9876543210",
                        "name": "City Hospital",
                        "specialty": "Hospital",
                        "networkStatus": "IN_NETWORK",
                        "tier": "TIER_1"
                    },
                    "insuranceData": {
                        "sequence": 1,
                        "primary": true,
                        "coverageReference": "COV-456",
                        "planName": "HMO Plus",
                        "planType": "HMO",
                        "groupNumber": "GRP-456"
                    },
                    "lineItems": []
                }
                """;

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-67890")
                .tenant("ANTHEM")
                .build();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertTrue(result.isSuccess());
        assertEquals("CLAIMS_INST", result.getTargetSystem());

        ConvertedPayload payload = result.getConvertedPayload();
        assertEquals("837I", payload.getClaimType());
        assertEquals("CLAIMS_INST", payload.getTargetSystem());
        assertEquals(2, payload.getPriority()); // urgent = 2
    }

    @Test
    void shouldConvertPharmacyClaim() {
        // Given
        String enrichedData = """
                {
                    "claimId": "CLM-003",
                    "claimType": "pharmacy",
                    "use": "claim",
                    "priority": "stat",
                    "created": "2024-01-15T10:00:00Z",
                    "totalValue": 75.00,
                    "patientData": {
                        "id": "P789"
                    },
                    "providerData": {
                        "id": "PHARM-001"
                    },
                    "insuranceData": {},
                    "lineItems": []
                }
                """;

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-RX-123")
                .tenant("ANTHEM")
                .build();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertTrue(result.isSuccess());
        assertEquals("CLAIMS_RX", result.getTargetSystem());

        ConvertedPayload payload = result.getConvertedPayload();
        assertEquals("NCPDP", payload.getClaimType());
        assertEquals("CLAIMS_RX", payload.getTargetSystem());
        assertEquals(1, payload.getPriority()); // stat = 1
    }

    @Test
    void shouldConvertDentalClaim() {
        // Given
        String enrichedData = """
                {
                    "claimId": "CLM-004",
                    "claimType": "dental",
                    "use": "claim",
                    "priority": "normal",
                    "created": "2024-01-15T10:00:00Z",
                    "totalValue": 300.00,
                    "patientData": {
                        "id": "P999"
                    },
                    "providerData": {
                        "id": "DENT-001"
                    },
                    "insuranceData": {},
                    "lineItems": []
                }
                """;

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-DENT-456")
                .tenant("ANTHEM")
                .build();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertTrue(result.isSuccess());
        assertEquals("CLAIMS_DENTAL", result.getTargetSystem());

        ConvertedPayload payload = result.getConvertedPayload();
        assertEquals("837D", payload.getClaimType());
        assertEquals("CLAIMS_DENTAL", payload.getTargetSystem());
        assertEquals(5, payload.getPriority()); // normal = 5
    }

    @Test
    void shouldHandleUnknownClaimType() {
        // Given
        String enrichedData = """
                {
                    "claimId": "CLM-005",
                    "claimType": "unknown",
                    "use": "claim",
                    "priority": "normal",
                    "created": "2024-01-15T10:00:00Z",
                    "totalValue": 100.00,
                    "patientData": {
                        "id": "P888"
                    },
                    "providerData": {
                        "id": "PROV-999"
                    },
                    "insuranceData": {},
                    "lineItems": []
                }
                """;

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-UNK-789")
                .tenant("ANTHEM")
                .build();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertTrue(result.isSuccess());
        assertEquals("CLAIMS_GENERIC", result.getTargetSystem());

        ConvertedPayload payload = result.getConvertedPayload();
        assertEquals("UNKNOWN", payload.getClaimType());
        assertEquals("CLAIMS_GENERIC", payload.getTargetSystem());
    }

    @Test
    void shouldIncludeProcessingMetadata() {
        // Given
        String enrichedData = """
                {
                    "claimId": "CLM-006",
                    "claimType": "professional",
                    "created": "2024-01-15T10:00:00Z",
                    "patientData": {"id": "P123"},
                    "providerData": {"id": "PROV-001"},
                    "insuranceData": {},
                    "lineItems": []
                }
                """;

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-META-123")
                .tenant("ANTHEM")
                .build();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertTrue(result.isSuccess());

        ConvertedPayload payload = result.getConvertedPayload();
        assertNotNull(payload.getProcessingMetadata());
        assertTrue(payload.getProcessingMetadata().containsKey("convertedAt"));
        assertTrue(payload.getProcessingMetadata().containsKey("converterVersion"));
        assertEquals("FHIR_R4", payload.getProcessingMetadata().get("sourceFormat"));
        assertEquals("CLAIMS_PRO_V1", payload.getProcessingMetadata().get("targetFormat"));
    }

    @Test
    void shouldHandleConversionError() {
        // Given - invalid JSON
        String enrichedData = "{ invalid json }";

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-ERR-999")
                .tenant("ANTHEM")
                .build();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void shouldSetConvertedAtTimestamp() {
        // Given
        String enrichedData = """
                {
                    "claimId": "CLM-007",
                    "claimType": "professional",
                    "created": "2024-01-15T10:00:00Z",
                    "patientData": {"id": "P123"},
                    "providerData": {"id": "PROV-001"},
                    "insuranceData": {},
                    "lineItems": []
                }
                """;

        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-TIME-123")
                .tenant("ANTHEM")
                .build();

        Instant beforeConversion = Instant.now();

        // When
        ConversionResult result = service.convertPayload(enrichedData, message);

        // Then
        assertTrue(result.isSuccess());

        ConvertedPayload payload = result.getConvertedPayload();
        assertNotNull(payload.getConvertedAt());
        assertTrue(payload.getConvertedAt().isAfter(beforeConversion.minusSeconds(5)));
        assertTrue(payload.getConvertedAt().isBefore(Instant.now().plusSeconds(5)));
    }
}
