package com.anthem.pagw.response.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.response.model.ClaimResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResponseBuilderService Tests")
class ResponseBuilderServiceTest {

    private ResponseBuilderService service;

    @BeforeEach
    void setUp() {
        service = new ResponseBuilderService();
    }

    @Test
    @DisplayName("Should build success response with complete data")
    void testBuildSuccessResponseWithCompleteData() {
        // Given
        String responseData = "{\"externalId\":\"EXT-12345\",\"status\":\"ACCEPTED\"}";
        PagwMessage message = createTestMessage();

        // When
        ClaimResponse response = service.buildSuccessResponse(responseData, message);

        // Then
        assertNotNull(response);
        assertEquals("ClaimResponse", response.getResourceType());
        assertEquals("complete", response.getOutcome());
        assertEquals("Claim accepted for processing", response.getDisposition());
        assertEquals("EXT-12345", response.getPreAuthRef());
        assertEquals("active", response.getStatus());
        assertEquals("claim", response.getUse());
        
        // Verify identifiers
        assertNotNull(response.getIdentifier());
        assertEquals(1, response.getIdentifier().size());
        assertEquals("urn:anthem:pagw:claim-response", response.getIdentifier().get(0).getSystem());
        assertEquals("PAGW-TEST-001", response.getIdentifier().get(0).getValue());
        
        // Verify process notes
        assertNotNull(response.getProcessNote());
        assertTrue(response.getProcessNote().size() >= 2);
        
        // Verify timestamps
        assertNotNull(response.getCreated());
        
        // Verify references
        assertEquals("Claim/PAGW-TEST-001", response.getRequestReference());
        assertEquals("Patient/PAT-001", response.getPatientReference());
        assertEquals("Organization/anthem", response.getInsurerReference());
        assertEquals("Anthem Blue Cross Blue Shield", response.getInsurerDisplay());
    }

    @Test
    @DisplayName("Should build success response with PENDING status")
    void testBuildSuccessResponseWithPendingStatus() {
        // Given
        String responseData = "{\"externalId\":\"EXT-67890\",\"status\":\"PENDING\"}";
        PagwMessage message = createTestMessage();

        // When
        ClaimResponse response = service.buildSuccessResponse(responseData, message);

        // Then
        assertNotNull(response);
        assertEquals("queued", response.getOutcome());
        assertEquals("Claim queued for review", response.getDisposition());
        assertEquals("EXT-67890", response.getPreAuthRef());
    }

    @Test
    @DisplayName("Should build success response without response data")
    void testBuildSuccessResponseWithNullData() {
        // Given
        PagwMessage message = createTestMessage();

        // When
        ClaimResponse response = service.buildSuccessResponse(null, message);

        // Then
        assertNotNull(response);
        assertEquals("complete", response.getOutcome());
        assertEquals("Claim received and processing", response.getDisposition());
        assertNull(response.getPreAuthRef());
        assertNotNull(response.getProcessNote());
    }

    @Test
    @DisplayName("Should build success response with invalid JSON")
    void testBuildSuccessResponseWithInvalidJson() {
        // Given
        String invalidJson = "{invalid json}";
        PagwMessage message = createTestMessage();

        // When
        ClaimResponse response = service.buildSuccessResponse(invalidJson, message);

        // Then
        assertNotNull(response);
        assertEquals("complete", response.getOutcome());
        // Should still create a valid response despite invalid JSON
        assertNotNull(response.getId());
    }

    @Test
    @DisplayName("Should build error response with error details")
    void testBuildErrorResponseWithDetails() {
        // Given
        PagwMessage message = createTestMessage();
        message.setErrorCode("VALIDATION_FAILED");
        message.setErrorMessage("Invalid FHIR bundle structure");

        // When
        ClaimResponse response = service.buildErrorResponse(message);

        // Then
        assertNotNull(response);
        assertEquals("ClaimResponse", response.getResourceType());
        assertEquals("error", response.getOutcome());
        assertEquals("Claim processing failed", response.getDisposition());
        
        // Verify errors
        assertNotNull(response.getErrors());
        assertEquals(1, response.getErrors().size());
        ClaimResponse.ClaimError error = response.getErrors().get(0);
        assertEquals("VALIDATION_FAILED", error.getCode());
        assertEquals("Invalid FHIR bundle structure", error.getMessage());
        assertEquals("error", error.getSeverity());
        
        // Verify process notes include error details
        assertNotNull(response.getProcessNote());
        assertTrue(response.getProcessNote().size() >= 3);
        assertTrue(response.getProcessNote().stream()
                .anyMatch(note -> note.getText().contains("Invalid FHIR bundle structure")));
    }

    @Test
    @DisplayName("Should build error response with default values when error details missing")
    void testBuildErrorResponseWithoutDetails() {
        // Given
        PagwMessage message = createTestMessage();
        // No error code or message set

        // When
        ClaimResponse response = service.buildErrorResponse(message);

        // Then
        assertNotNull(response);
        assertEquals("error", response.getOutcome());
        assertNotNull(response.getErrors());
        assertEquals(1, response.getErrors().size());
        
        ClaimResponse.ClaimError error = response.getErrors().get(0);
        assertEquals("PROCESSING_ERROR", error.getCode());
        assertEquals("An error occurred during processing", error.getMessage());
    }

    @Test
    @DisplayName("Should include patient reference from metadata")
    void testPatientReferenceFromMetadata() {
        // Given
        PagwMessage message = createTestMessage();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("patientReference", "Patient/12345");
        message.setMetadata(metadata);

        // When
        ClaimResponse response = service.buildSuccessResponse(null, message);

        // Then
        assertEquals("Patient/12345", response.getPatientReference());
    }

    @Test
    @DisplayName("Should handle missing metadata gracefully")
    void testMissingMetadata() {
        // Given
        PagwMessage message = createTestMessage();
        message.setMetadata(null);

        // When
        ClaimResponse response = service.buildSuccessResponse(null, message);

        // Then
        assertNotNull(response);
        assertNull(response.getPatientReference());
    }

    @Test
    @DisplayName("Should create unique IDs for each response")
    void testUniqueResponseIds() {
        // Given
        PagwMessage message = createTestMessage();

        // When
        ClaimResponse response1 = service.buildSuccessResponse(null, message);
        ClaimResponse response2 = service.buildSuccessResponse(null, message);

        // Then
        assertNotNull(response1.getId());
        assertNotNull(response2.getId());
        assertNotEquals(response1.getId(), response2.getId());
    }

    @Test
    @DisplayName("Should format timestamps in ISO format")
    void testTimestampFormat() {
        // Given
        PagwMessage message = createTestMessage();

        // When
        ClaimResponse response = service.buildSuccessResponse(null, message);

        // Then
        assertNotNull(response.getCreated());
        // Should match ISO 8601 format (basic check)
        assertTrue(response.getCreated().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"));
    }

    // Helper method to create test message
    private PagwMessage createTestMessage() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("patientReference", "Patient/PAT-001");
        
        return PagwMessage.builder()
                .pagwId("PAGW-TEST-001")
                .correlationId("CORR-001")
                .tenant("TENANT-001")
                .stage("RESPONSE_BUILDING")
                .payloadBucket("test-bucket")
                .payloadKey("test/key")
                .metadata(metadata)
                .build();
    }
}
