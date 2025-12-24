package com.elevance.pagw.mockpayer.controller;

import com.elevance.pagw.mockpayer.model.*;
import com.elevance.pagw.mockpayer.service.MockPayerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MockPayerController
 */
@ExtendWith(MockitoExtension.class)
class MockPayerControllerTest {

    @Mock
    private MockPayerService mockPayerService;

    @InjectMocks
    private MockPayerController mockPayerController;

    private Map<String, Object> testRequest;
    private String testCorrelationId;

    @BeforeEach
    void setUp() {
        testRequest = new HashMap<>();
        testRequest.put("procedureCode", "99213");
        testRequest.put("patientId", "PAT123");
        testCorrelationId = "test-corr-123";
    }

    @Test
    void submitPriorAuth_withDefaultRequest_shouldReturnApprovedResponse() {
        // Arrange
        ApprovedResponse approvedResponse = ApprovedResponse.builder()
                .responseCode("A1")
                .responseStatus("APPROVED")
                .message("Prior authorization approved")
                .authorizationNumber("AUTH-123456")
                .build();
        
        when(mockPayerService.handleApprovedScenario(any(), anyString()))
                .thenReturn(ResponseEntity.ok(approvedResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, "REQ-123", "Bearer token123");

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(mockPayerService).handleApprovedScenario(testRequest, testCorrelationId);
    }

    @Test
    void submitClaim_withDefaultRequest_shouldReturnApprovedResponse() {
        // Arrange
        ApprovedResponse approvedResponse = ApprovedResponse.builder()
                .responseCode("A1")
                .responseStatus("APPROVED")
                .build();
        
        when(mockPayerService.handleApprovedScenario(any(), anyString()))
                .thenReturn(ResponseEntity.ok(approvedResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitClaim(
                testRequest, "PAGW-123", testCorrelationId, "CLAIMS_PRO", "TENANT1");

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(mockPayerService).handleApprovedScenario(testRequest, testCorrelationId);
    }

    @Test
    void submitPriorAuth_withTimeoutTest_shouldInvokeTimeoutScenario() {
        // Arrange
        testRequest.put("testTrigger", "TIMEOUT-TEST");
        ApprovedResponse approvedResponse = ApprovedResponse.builder()
                .responseCode("A1")
                .responseStatus("APPROVED")
                .message("This response was intentionally delayed")
                .build();
        
        when(mockPayerService.handleTimeoutScenario(anyString()))
                .thenReturn(ResponseEntity.ok(approvedResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(mockPayerService).handleTimeoutScenario(testCorrelationId);
        verify(mockPayerService, never()).handleApprovedScenario(any(), anyString());
    }

    @Test
    void submitPriorAuth_withErrorTest_shouldInvokeErrorScenario() {
        // Arrange
        testRequest.put("testTrigger", "ERROR-TEST");
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("INTERNAL_SERVER_ERROR")
                .errorCode("E500")
                .build();
        
        when(mockPayerService.handleErrorScenario(anyString()))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        verify(mockPayerService).handleErrorScenario(testCorrelationId);
    }

    @Test
    void submitPriorAuth_withRateLimitTest_shouldInvokeRateLimitScenario() {
        // Arrange
        testRequest.put("testTrigger", "RATELIMIT-TEST");
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("RATE_LIMIT_EXCEEDED")
                .errorCode("E429")
                .build();
        
        when(mockPayerService.handleRateLimitScenario(anyString()))
                .thenReturn(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        verify(mockPayerService).handleRateLimitScenario(testCorrelationId);
    }

    @Test
    void submitPriorAuth_withUnauthTest_shouldInvokeUnauthorizedScenario() {
        // Arrange
        testRequest.put("testTrigger", "UNAUTH-TEST");
        ErrorResponse errorResponse = ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .errorCode("E401")
                .build();
        
        when(mockPayerService.handleUnauthorizedScenario())
                .thenReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(mockPayerService).handleUnauthorizedScenario();
    }

    @Test
    void submitPriorAuth_withBadRequestTest_shouldInvokeBadRequestScenario() {
        // Arrange
        testRequest.put("testTrigger", "BADREQUEST-TEST");
        ValidationErrorResponse errorResponse = ValidationErrorResponse.builder()
                .error("VALIDATION_ERROR")
                .errorCode("E400")
                .validationErrors(List.of())
                .build();
        
        when(mockPayerService.handleBadRequestScenario(anyString()))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(mockPayerService).handleBadRequestScenario(testCorrelationId);
    }

    @Test
    void submitPriorAuth_withDenyTest_shouldInvokeDeniedScenario() {
        // Arrange
        testRequest.put("testTrigger", "DENY-TEST");
        DeniedResponse deniedResponse = DeniedResponse.builder()
                .responseCode("A2")
                .responseStatus("DENIED")
                .build();
        
        when(mockPayerService.handleDeniedScenario(any(), anyString()))
                .thenReturn(ResponseEntity.ok(deniedResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(mockPayerService).handleDeniedScenario(testRequest, testCorrelationId);
    }

    @Test
    void submitPriorAuth_withPendTest_shouldInvokePendedScenario() {
        // Arrange
        testRequest.put("testTrigger", "PEND-TEST");
        PendedResponse pendedResponse = PendedResponse.builder()
                .responseCode("A3")
                .responseStatus("PENDED")
                .build();
        
        when(mockPayerService.handlePendedScenario(any(), anyString()))
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(pendedResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(mockPayerService).handlePendedScenario(testRequest, testCorrelationId);
    }

    @Test
    void submitPriorAuth_withMoreInfoTest_shouldInvokeMoreInfoScenario() {
        // Arrange
        testRequest.put("testTrigger", "MOREINFO-TEST");
        MoreInfoResponse moreInfoResponse = MoreInfoResponse.builder()
                .responseCode("A4")
                .responseStatus("ADDITIONAL_INFO_REQUIRED")
                .build();
        
        when(mockPayerService.handleMoreInfoScenario(any(), anyString()))
                .thenReturn(ResponseEntity.ok(moreInfoResponse));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(mockPayerService).handleMoreInfoScenario(testRequest, testCorrelationId);
    }

    @Test
    void checkStatus_shouldReturnStatusResponse() {
        // Arrange
        String trackingId = "TRACK-123";

        // Act
        ResponseEntity<StatusResponse> response = mockPayerController.checkStatus(
                trackingId, testCorrelationId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        StatusResponse body = response.getBody();
        assertEquals(trackingId, body.getTrackingId());
        assertEquals("APPROVED", body.getStatus());
        assertNotNull(body.getLastUpdated());
    }

    @Test
    void health_shouldReturnHealthStatus() {
        // Act
        ResponseEntity<Map<String, Object>> response = mockPayerController.health();

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        Map<String, Object> body = response.getBody();
        assertEquals("UP", body.get("status"));
        assertEquals("Mock Payer API", body.get("service"));
        assertEquals("1.0.0", body.get("version"));
        assertNotNull(body.get("timestamp"));
    }

    @Test
    void submitPriorAuth_withException_shouldReturnInternalServerError() {
        // Arrange
        when(mockPayerService.handleApprovedScenario(any(), anyString()))
                .thenThrow(new RuntimeException("Test exception"));

        // Act
        ResponseEntity<?> response = mockPayerController.submitPriorAuth(
                testRequest, testCorrelationId, null, null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof ErrorResponse);
        
        ErrorResponse errorBody = (ErrorResponse) response.getBody();
        assertEquals("INTERNAL_SERVER_ERROR", errorBody.getError());
        assertEquals("E500", errorBody.getErrorCode());
    }
}
