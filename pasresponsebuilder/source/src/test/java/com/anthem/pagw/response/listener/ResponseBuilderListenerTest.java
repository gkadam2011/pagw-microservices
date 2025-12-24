package com.anthem.pagw.response.listener;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.response.model.ClaimResponse;
import com.anthem.pagw.response.service.ResponseBuilderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseBuilderListener Tests")
class ResponseBuilderListenerTest {

    @Mock
    private ResponseBuilderService responseBuilderService;

    @Mock
    private S3Service s3Service;

    @Mock
    private RequestTrackerService trackerService;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private ResponseBuilderListener listener;

    private PagwMessage testMessage;
    private String testMessageJson;

    @BeforeEach
    void setUp() {
        testMessage = createTestMessage();
        testMessageJson = JsonUtils.toJson(testMessage);
    }

    @Test
    @DisplayName("Should process success message and route to subscription handler")
    void testHandleSuccessMessage() throws Exception {
        // Given
        String responseData = "{\"externalId\":\"EXT-123\",\"status\":\"ACCEPTED\"}";
        ClaimResponse claimResponse = createSuccessClaimResponse();
        
        when(s3Service.getObject(anyString(), anyString())).thenReturn(responseData);
        when(responseBuilderService.buildSuccessResponse(anyString(), any(PagwMessage.class)))
                .thenReturn(claimResponse);

        // When
        listener.handleMessage(testMessageJson, "PAGW-TEST-001");

        // Then
        // Verify status update
        verify(trackerService).updateStatus("PAGW-TEST-001", "BUILDING_RESPONSE", "response-builder");
        
        // Verify S3 operations
        verify(s3Service).getObject("test-bucket", "test/response-key");
        ArgumentCaptor<String> s3KeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).putObject(eq("test-bucket"), s3KeyCaptor.capture(), anyString());
        
        // Verify S3 key contains pagwId and follows pattern
        String capturedS3Key = s3KeyCaptor.getValue();
        assertTrue(capturedS3Key.contains("PAGW-TEST-001"));
        assertTrue(capturedS3Key.contains("response"));
        
        // Verify response building
        verify(responseBuilderService).buildSuccessResponse(eq(responseData), any(PagwMessage.class));
        
        // Verify final status update
        ArgumentCaptor<String> s3KeyFinalCaptor = ArgumentCaptor.forClass(String.class);
        verify(trackerService).updateFinalStatus(
                eq("PAGW-TEST-001"),
                eq("COMPLETED"),
                eq("response-builder"),
                eq("test-bucket"),
                s3KeyFinalCaptor.capture()
        );
        
        // Verify the captured S3 key
        String finalS3Key = s3KeyFinalCaptor.getValue();
        assertTrue(finalS3Key.contains("PAGW-TEST-001"));
        assertTrue(finalS3Key.contains("response"));
        
        // Verify routing to subscription handler
        ArgumentCaptor<String> queueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);
        verify(outboxService).writeOutbox(queueCaptor.capture(), messageCaptor.capture());
        
        assertEquals("pagw-subscription-handler-queue", queueCaptor.getValue());
        PagwMessage routedMessage = messageCaptor.getValue();
        assertEquals("SUBSCRIPTION_HANDLER", routedMessage.getStage());
        assertEquals("complete", routedMessage.getApiResponseStatus());
    }

    @Test
    @DisplayName("Should process error message and build error response")
    void testHandleErrorMessage() throws Exception {
        // Given
        testMessage.setErrorCode("VALIDATION_FAILED");
        testMessage.setErrorMessage("Invalid bundle");
        testMessageJson = JsonUtils.toJson(testMessage);
        
        ClaimResponse errorResponse = createErrorClaimResponse();
        when(responseBuilderService.buildErrorResponse(any(PagwMessage.class)))
                .thenReturn(errorResponse);

        // When
        listener.handleMessage(testMessageJson, "PAGW-TEST-001");

        // Then
        verify(responseBuilderService).buildErrorResponse(any(PagwMessage.class));
        verify(responseBuilderService, never()).buildSuccessResponse(anyString(), any(PagwMessage.class));
        
        // Verify error status
        verify(trackerService).updateFinalStatus(
                eq("PAGW-TEST-001"),
                eq("COMPLETED_WITH_ERRORS"),
                eq("response-builder"),
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("Should handle S3 fetch failure gracefully")
    void testHandleS3FetchFailure() throws Exception {
        // Given
        when(s3Service.getObject(anyString(), anyString()))
                .thenThrow(new RuntimeException("S3 error"));
        
        ClaimResponse claimResponse = createSuccessClaimResponse();
        when(responseBuilderService.buildSuccessResponse(isNull(), any(PagwMessage.class)))
                .thenReturn(claimResponse);

        // When
        listener.handleMessage(testMessageJson, "PAGW-TEST-001");

        // Then
        // Should still build response with null data
        verify(responseBuilderService).buildSuccessResponse(isNull(), any(PagwMessage.class));
        verify(trackerService).updateFinalStatus(
                eq("PAGW-TEST-001"),
                eq("COMPLETED"),
                eq("response-builder"),
                anyString(),
                anyString()
        );
    }

    @Test
    @DisplayName("Should handle message without payload key")
    void testHandleMessageWithoutPayloadKey() throws Exception {
        // Given
        testMessage.setPayloadKey(null);
        testMessageJson = JsonUtils.toJson(testMessage);
        
        ClaimResponse claimResponse = createSuccessClaimResponse();
        when(responseBuilderService.buildSuccessResponse(isNull(), any(PagwMessage.class)))
                .thenReturn(claimResponse);

        // When
        listener.handleMessage(testMessageJson, "PAGW-TEST-001");

        // Then
        verify(s3Service, never()).getObject(anyString(), anyString());
        verify(responseBuilderService).buildSuccessResponse(isNull(), any(PagwMessage.class));
    }

    @Test
    @DisplayName("Should handle processing exception and update error status")
    void testHandleProcessingException() {
        // Given
        when(s3Service.getObject(anyString(), anyString()))
                .thenThrow(new RuntimeException("Unexpected error"));
        when(responseBuilderService.buildSuccessResponse(any(), any(PagwMessage.class)))
                .thenThrow(new RuntimeException("Build error"));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            listener.handleMessage(testMessageJson, "PAGW-TEST-001");
        });

        // Verify error tracking
        verify(trackerService).updateStatus(
                eq("PAGW-TEST-001"),
                eq("RESPONSE_BUILD_ERROR"),
                eq("response-builder"),
                eq("EXCEPTION"),
                anyString()
        );
    }

    @Test
    @DisplayName("Should handle invalid JSON message")
    void testHandleInvalidJson() {
        // Given
        String invalidJson = "{invalid json}";

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            listener.handleMessage(invalidJson, null);
        });
    }

    @Test
    @DisplayName("Should store ClaimResponse in standardized S3 path")
    void testS3PathStandardization() throws Exception {
        // Given
        ClaimResponse claimResponse = createSuccessClaimResponse();
        when(responseBuilderService.buildSuccessResponse(any(), any(PagwMessage.class)))
                .thenReturn(claimResponse);

        // When
        listener.handleMessage(testMessageJson, "PAGW-TEST-001");

        // Then
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).putObject(eq("test-bucket"), keyCaptor.capture(), anyString());
        
        String capturedKey = keyCaptor.getValue();
        assertTrue(capturedKey.contains("PAGW-TEST-001"), 
                "S3 key should contain pagwId");
        assertTrue(capturedKey.contains("response"), 
                "S3 key should follow standardized naming with 'response'");
        assertTrue(capturedKey.endsWith(".json") || capturedKey.endsWith("fhir.json"),
                "S3 key should end with .json");
    }

    @Test
    @DisplayName("Should propagate correlation and tenant IDs")
    void testCorrelationAndTenantPropagation() throws Exception {
        // Given
        testMessage.setCorrelationId("CORR-123");
        testMessage.setTenant("TENANT-ABC");
        testMessageJson = JsonUtils.toJson(testMessage);
        
        ClaimResponse claimResponse = createSuccessClaimResponse();
        when(responseBuilderService.buildSuccessResponse(any(), any(PagwMessage.class)))
                .thenReturn(claimResponse);

        // When
        listener.handleMessage(testMessageJson, "PAGW-TEST-001");

        // Then
        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);
        verify(outboxService).writeOutbox(anyString(), messageCaptor.capture());
        
        PagwMessage routedMessage = messageCaptor.getValue();
        assertEquals("CORR-123", routedMessage.getCorrelationId());
        assertEquals("TENANT-ABC", routedMessage.getTenant());
    }

    @Test
    @DisplayName("Should set correct outcome status for complete response")
    void testCompleteOutcomeStatus() throws Exception {
        // Given
        ClaimResponse claimResponse = createSuccessClaimResponse();
        claimResponse.setOutcome("complete");
        when(responseBuilderService.buildSuccessResponse(any(), any(PagwMessage.class)))
                .thenReturn(claimResponse);

        // When
        listener.handleMessage(testMessageJson, "PAGW-TEST-001");

        // Then
        verify(trackerService).updateFinalStatus(
                eq("PAGW-TEST-001"),
                eq("COMPLETED"),
                anyString(),
                anyString(),
                anyString()
        );
    }

    // Helper methods
    private PagwMessage createTestMessage() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("patientReference", "Patient/PAT-001");
        
        return PagwMessage.builder()
                .pagwId("PAGW-TEST-001")
                .correlationId("CORR-001")
                .tenant("TENANT-001")
                .stage("RESPONSE_BUILDING")
                .payloadBucket("test-bucket")
                .payloadKey("test/response-key")
                .metadata(metadata)
                .build();
    }

    private ClaimResponse createSuccessClaimResponse() {
        ClaimResponse response = new ClaimResponse();
        response.setResourceType("ClaimResponse");
        response.setId("response-123");
        response.setOutcome("complete");
        response.setDisposition("Claim accepted");
        response.setStatus("active");
        
        ClaimResponse.Identifier identifier = new ClaimResponse.Identifier();
        identifier.setSystem("urn:anthem:pagw");
        identifier.setValue("PAGW-TEST-001");
        response.setIdentifier(List.of(identifier));
        
        return response;
    }

    private ClaimResponse createErrorClaimResponse() {
        ClaimResponse response = createSuccessClaimResponse();
        response.setOutcome("error");
        response.setDisposition("Processing failed");
        
        ClaimResponse.ClaimError error = new ClaimResponse.ClaimError();
        error.setCode("VALIDATION_FAILED");
        error.setMessage("Invalid bundle");
        error.setSeverity("error");
        response.setErrors(List.of(error));
        
        return response;
    }
}
