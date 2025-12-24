package com.anthem.pagw.callback.service;

import com.anthem.pagw.callback.model.CallbackResult;
import com.anthem.pagw.core.model.PagwMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CallbackHandlerService.
 */
@ExtendWith(MockitoExtension.class)
class CallbackHandlerServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CallbackHandlerService service;

    @BeforeEach
    void setUp() {
        service = new CallbackHandlerService(jdbcTemplate);
    }

    @Test
    void testProcessCallback_AcceptedStatus_ReturnsCompleted() {
        // Arrange
        String responseData = "{\"status\":\"accepted\",\"claimNumber\":\"CLM-12345\"}";
        PagwMessage message = createTestMessage("ACCEPTED");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertEquals("complete", result.getOutcome());
        assertFalse(result.isError());
        assertEquals("Claim processed successfully", result.getDisposition());
        assertEquals("PAGW-TEST-001", result.getPagwId());
    }

    @Test
    void testProcessCallback_PendingStatus_ReturnsPending() {
        // Arrange
        String responseData = "{\"status\":\"pending\"}";
        PagwMessage message = createTestMessage("PENDING");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertEquals("PENDING", result.getStatus());
        assertEquals("queued", result.getOutcome());
        assertFalse(result.isError());
        assertEquals("Claim still being processed", result.getDisposition());
    }

    @Test
    void testProcessCallback_RejectedStatus_ReturnsRejected() {
        // Arrange
        String responseData = "{\"status\":\"rejected\",\"error\":\"Invalid claim\"}";
        PagwMessage message = createTestMessage("REJECTED");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertEquals("REJECTED", result.getStatus());
        assertEquals("error", result.getOutcome());
        assertTrue(result.isError());
        assertEquals("CLAIM_REJECTED", result.getErrorCode());
    }

    @Test
    void testProcessCallback_ErrorStatus_ReturnsError() {
        // Arrange
        String responseData = "{\"status\":\"error\",\"message\":\"System error\"}";
        PagwMessage message = createTestMessage("ERROR");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertEquals("ERROR", result.getStatus());
        assertEquals("error", result.getOutcome());
        assertTrue(result.isError());
        assertEquals("PROCESSING_ERROR", result.getErrorCode());
    }

    @Test
    void testProcessCallback_UnknownStatus_ReturnsUnknown() {
        // Arrange
        String responseData = "{\"status\":\"unknown\"}";
        PagwMessage message = createTestMessage("UNKNOWN_STATUS");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertEquals("UNKNOWN", result.getStatus());
        assertEquals("queued", result.getOutcome());
        assertFalse(result.isError());
    }

    @Test
    void testProcessCallback_NullStatus_HandlesGracefully() {
        // Arrange
        String responseData = "{\"data\":\"test\"}";
        PagwMessage message = createTestMessage(null);

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertEquals("UNKNOWN", result.getStatus());
        assertNotNull(result.getProcessedAt());
    }

    @Test
    void testProcessCallback_ComplexResponse_ExtractsData() {
        // Arrange
        String responseData = "{\"rawResponse\":\"{\\\"claimNumber\\\":\\\"CLM-999\\\",\\\"adjudicationDate\\\":\\\"2025-12-24\\\"}\"}";
        PagwMessage message = createTestMessage("COMPLETE");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertEquals("COMPLETED", result.getStatus());
        // Service should extract claim number from rawResponse
    }

    @Test
    void testProcessCallback_InvalidJson_HandlesException() {
        // Arrange
        String responseData = "invalid-json";
        PagwMessage message = createTestMessage("ACCEPTED");

        // Act & Assert
        assertDoesNotThrow(() -> {
            CallbackResult result = service.processCallback(responseData, message);
            assertNotNull(result);
        });
    }

    @Test
    void testProcessCallback_EmptyResponse_HandlesGracefully() {
        // Arrange
        String responseData = "{}";
        PagwMessage message = createTestMessage("ACCEPTED");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertNotNull(result);
        assertEquals("PAGW-TEST-001", result.getPagwId());
    }

    @Test
    void testProcessCallback_PreservesExternalReferenceId() {
        // Arrange
        String responseData = "{\"status\":\"accepted\"}";
        PagwMessage message = createTestMessage("ACCEPTED");
        message.setExternalReferenceId("EXT-REF-12345");

        // Act
        CallbackResult result = service.processCallback(responseData, message);

        // Assert
        assertEquals("EXT-REF-12345", result.getExternalReferenceId());
    }

    private PagwMessage createTestMessage(String apiResponseStatus) {
        PagwMessage message = new PagwMessage();
        message.setPagwId("PAGW-TEST-001");
        message.setTenant("ANTHEM");
        message.setCorrelationId("test-corr-123");
        message.setApiResponseStatus(apiResponseStatus);
        return message;
    }
}
