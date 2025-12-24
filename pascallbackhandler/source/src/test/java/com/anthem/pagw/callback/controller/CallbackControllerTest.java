package com.anthem.pagw.callback.controller;

import com.anthem.pagw.callback.model.CallbackRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CallbackController (simplified without Spring context).
 */
class CallbackControllerTest {

    @Test
    void testCallbackRequest_HasRequiredFields() {
        // Arrange & Act
        CallbackRequest request = new CallbackRequest();
        request.setPagwId("PAGW-TEST-001");
        request.setStatus("ACCEPTED");
        request.setOutcome("complete");

        // Assert
        assertNotNull(request.getPagwId());
        assertEquals("PAGW-TEST-001", request.getPagwId());
        assertEquals("ACCEPTED", request.getStatus());
        assertEquals("complete", request.getOutcome());
    }

    @Test
    void testCallbackRequest_AllFields() {
        // Arrange & Act
        CallbackRequest request = new CallbackRequest();
        request.setPagwId("PAGW-TEST-002");
        request.setReferenceId("REF-123");
        request.setStatus("REJECTED");
        request.setOutcome("error");
        request.setClaimNumber("CLM-999");
        request.setErrorCode("ERR-001");
        request.setErrorMessage("Test error");

        // Assert
        assertNotNull(request);
        assertEquals("PAGW-TEST-002", request.getPagwId());
        assertEquals("REF-123", request.getReferenceId());
        assertEquals("REJECTED", request.getStatus());
        assertEquals("error", request.getOutcome());
        assertEquals("CLM-999", request.getClaimNumber());
        assertEquals("ERR-001", request.getErrorCode());
        assertEquals("Test error", request.getErrorMessage());
    }

    @Test
    void testCallbackRequest_NullSafe() {
        // Arrange & Act
        CallbackRequest request = new CallbackRequest();

        // Assert - should not throw NPE
        assertDoesNotThrow(() -> {
            request.getPagwId();
            request.getStatus();
            request.getOutcome();
        });
    }
}
