package com.elevance.pagw.mockpayer.service;

import com.elevance.pagw.mockpayer.model.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MockPayerService
 */
@ExtendWith(MockitoExtension.class)
class MockPayerServiceTest {

    @InjectMocks
    private MockPayerService mockPayerService;

    @Test
    void handleApprovedScenario_shouldReturnApprovedResponse() {
        // Arrange
        Map<String, Object> request = new HashMap<>();
        request.put("procedureCode", "99213");
        String correlationId = "test-correlation-123";

        // Act
        ResponseEntity<ApprovedResponse> response = mockPayerService.handleApprovedScenario(request, correlationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        ApprovedResponse body = response.getBody();
        assertEquals("A1", body.getResponseCode());
        assertEquals("APPROVED", body.getResponseStatus());
        assertEquals("Prior authorization approved", body.getMessage());
        assertNotNull(body.getAuthorizationNumber());
        assertTrue(body.getAuthorizationNumber().startsWith("AUTH-"));
        assertNotNull(body.getApprovedServices());
        assertFalse(body.getApprovedServices().isEmpty());
        assertEquals("1234567890", body.getProviderNpi());
        assertEquals("Mock Payer Inc.", body.getPayerName());
    }

    @Test
    void handleDeniedScenario_shouldReturnDeniedResponse() {
        // Arrange
        Map<String, Object> request = new HashMap<>();
        request.put("procedureCode", "99213");
        String correlationId = "test-correlation-456";

        // Act
        ResponseEntity<DeniedResponse> response = mockPayerService.handleDeniedScenario(request, correlationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        DeniedResponse body = response.getBody();
        assertEquals("A2", body.getResponseCode());
        assertEquals("DENIED", body.getResponseStatus());
        assertEquals("Prior authorization denied", body.getMessage());
        assertNotNull(body.getDenialReasons());
        assertFalse(body.getDenialReasons().isEmpty());
        assertEquals(2, body.getDenialReasons().size());
        assertNotNull(body.getAppealInstructions());
        assertEquals("1234567890", body.getProviderNpi());
    }

    @Test
    void handlePendedScenario_shouldReturnPendedResponse() {
        // Arrange
        Map<String, Object> request = new HashMap<>();
        request.put("procedureCode", "99214");
        String correlationId = "test-correlation-789";

        // Act
        ResponseEntity<PendedResponse> response = mockPayerService.handlePendedScenario(request, correlationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        
        PendedResponse body = response.getBody();
        assertEquals("A3", body.getResponseCode());
        assertEquals("PENDED", body.getResponseStatus());
        assertEquals("Request received and pending clinical review", body.getMessage());
        assertEquals("CLINICAL_REVIEW_REQUIRED", body.getPendReason());
        assertNotNull(body.getPendDetails());
        assertEquals("MEDICAL_DIRECTOR", body.getPendDetails().getReviewType());
        assertEquals(24, body.getPendDetails().getEstimatedCompletionHours());
        assertNotNull(body.getStatusCheckInfo());
        assertNotNull(body.getCallbackInfo());
        assertTrue(body.getCallbackInfo().getWillCallback());
    }

    @Test
    void handleMoreInfoScenario_shouldReturnMoreInfoResponse() {
        // Arrange
        Map<String, Object> request = new HashMap<>();
        request.put("procedureCode", "99215");
        String correlationId = "test-correlation-101";

        // Act
        ResponseEntity<MoreInfoResponse> response = mockPayerService.handleMoreInfoScenario(request, correlationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        MoreInfoResponse body = response.getBody();
        assertEquals("A4", body.getResponseCode());
        assertEquals("ADDITIONAL_INFO_REQUIRED", body.getResponseStatus());
        assertNotNull(body.getRequiredDocuments());
        assertEquals(3, body.getRequiredDocuments().size());
        
        RequiredDocument firstDoc = body.getRequiredDocuments().get(0);
        assertEquals("CLINICAL_NOTES", firstDoc.getDocumentType());
        assertTrue(firstDoc.getRequired());
        
        assertNotNull(body.getSubmissionInfo());
        assertNotNull(body.getSubmissionInfo().getReferenceNumber());
        assertTrue(body.getSubmissionInfo().getReferenceNumber().startsWith("REF-"));
        assertEquals(25, body.getSubmissionInfo().getMaxFileSizeMB());
        
        assertNotNull(body.getContactInfo());
        assertEquals("1-800-555-0123", body.getContactInfo().getPhone());
    }

    @Test
    void handleErrorScenario_shouldReturn500Error() {
        // Arrange
        String correlationId = "test-correlation-error";

        // Act
        ResponseEntity<ErrorResponse> response = mockPayerService.handleErrorScenario(correlationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        
        ErrorResponse body = response.getBody();
        assertEquals("INTERNAL_SERVER_ERROR", body.getError());
        assertEquals("E500", body.getErrorCode());
        assertEquals("An unexpected error occurred while processing your request", body.getMessage());
    }

    @Test
    @Disabled("Test takes 35 seconds - enable for manual testing")
    void handleTimeoutScenario_shouldReturnApprovedAfterDelay() {
        // Arrange
        String correlationId = "test-correlation-timeout";

        // Act
        long startTime = System.currentTimeMillis();
        ResponseEntity<ApprovedResponse> response = mockPayerService.handleTimeoutScenario(correlationId);
        long duration = System.currentTimeMillis() - startTime;

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(duration >= 35000, "Should delay at least 35 seconds");
        
        ApprovedResponse body = response.getBody();
        assertEquals("A1", body.getResponseCode());
        assertEquals("APPROVED", body.getResponseStatus());
        assertTrue(body.getMessage().contains("delayed"));
    }

    @Test
    void handleRateLimitScenario_shouldReturn429RateLimited() {
        // Arrange
        String correlationId = "test-correlation-ratelimit";

        // Act
        ResponseEntity<ErrorResponse> response = mockPayerService.handleRateLimitScenario(correlationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getHeaders().get("Retry-After"));
        
        ErrorResponse body = response.getBody();
        assertEquals("RATE_LIMIT_EXCEEDED", body.getError());
        assertEquals("E429", body.getErrorCode());
        assertTrue(body.getMessage().contains("rate limit"));
    }

    @Test
    void handleUnauthorizedScenario_shouldReturn401Unauthorized() {
        // Act
        ResponseEntity<ErrorResponse> response = mockPayerService.handleUnauthorizedScenario();

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        
        ErrorResponse body = response.getBody();
        assertEquals("UNAUTHORIZED", body.getError());
        assertEquals("E401", body.getErrorCode());
        assertTrue(body.getMessage().contains("Authentication failed"));
    }

    @Test
    void handleBadRequestScenario_shouldReturn400BadRequest() {
        // Arrange
        String correlationId = "test-correlation-badrequest";

        // Act
        ResponseEntity<ValidationErrorResponse> response = mockPayerService.handleBadRequestScenario(correlationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        
        ValidationErrorResponse body = response.getBody();
        assertEquals("BAD_REQUEST", body.getError());
        assertEquals("E400", body.getErrorCode());
        assertNotNull(body.getValidationErrors());
        assertFalse(body.getValidationErrors().isEmpty());
        
        ValidationError firstError = body.getValidationErrors().get(0);
        assertEquals("subscriberId", firstError.getField());
        assertEquals("REQUIRED", firstError.getCode());
    }
}
