package com.anthem.pagw.connector.listener;

import com.anthem.pagw.connector.client.ExternalApiClient;
import com.anthem.pagw.connector.model.ApiResponse;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiConnectorListener.
 */
@ExtendWith(MockitoExtension.class)
class ApiConnectorListenerTest {

    @Mock
    private ExternalApiClient externalApiClient;

    @Mock
    private S3Service s3Service;

    @Mock
    private RequestTrackerService trackerService;

    @Mock
    private EventTrackerService eventTrackerService;

    @Mock
    private OutboxService outboxService;

    private ApiConnectorListener listener;

    private static final String CALLBACK_QUEUE = "pagw-callback-handler-queue";

    @BeforeEach
    void setUp() {
        listener = new ApiConnectorListener(
                externalApiClient,
                s3Service,
                trackerService,
                eventTrackerService,
                outboxService,
                CALLBACK_QUEUE
        );
    }

    @Test
    void shouldSubmitToExternalSystemSuccessfully() {
        // Given
        String pagwId = "PAGW-12345";
        String convertedPayload = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .messageId("msg-123")
                .pagwId(pagwId)
                .schemaVersion("1.0")
                .stage("API_CONNECTOR")
                .tenant("ANTHEM")
                .payloadBucket("pagw-request-dev")
                .payloadKey("canonical/PAGW-12345.json")
                .targetSystem("CLAIMS_PRO")
                .metadata(Map.of("priority", "normal"))
                .createdAt(Instant.now())
                .build();

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setExternalId("EXT-12345");
        apiResponse.setStatus("SUBMITTED");
        apiResponse.setAsync(false);
        apiResponse.setHttpStatus(200);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(convertedPayload);
        when(externalApiClient.submitToExternalSystem(anyString(), anyString(), any())).thenReturn(apiResponse);

        // When
        listener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(trackerService).updateStatus(pagwId, "SUBMITTING", "api-connector");
        verify(s3Service).getObject("pagw-request-dev", "canonical/PAGW-12345.json");
        verify(externalApiClient).submitToExternalSystem("CLAIMS_PRO", convertedPayload, message);
        verify(s3Service).putObject(eq("pagw-request-dev"), contains("payer_raw.json"), anyString());
        verify(trackerService).updateStatus(pagwId, "SUBMITTED", "api-connector");
        verify(trackerService).updateExternalReference(pagwId, "EXT-12345");
        verify(outboxService).writeOutbox(eq(CALLBACK_QUEUE), any(PagwMessage.class));
    }

    @Test
    void shouldHandleAsyncResponse() {
        // Given
        String pagwId = "PAGW-ASYNC-123";
        String convertedPayload = "{\"claimId\":\"CLM-002\"}";

        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("canonical/PAGW-ASYNC-123.json")
                .targetSystem("CARELON")
                .build();

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setExternalId("CARELON-999");
        apiResponse.setStatus("PENDING");
        apiResponse.setAsync(true);
        apiResponse.setHttpStatus(202);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(convertedPayload);
        when(externalApiClient.submitToExternalSystem(anyString(), anyString(), any())).thenReturn(apiResponse);

        // When
        listener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(trackerService).updateStatus(pagwId, "AWAITING_CALLBACK", "api-connector");
        verify(trackerService).updateExternalReference(pagwId, "CARELON-999");
        
        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);
        verify(outboxService).writeOutbox(eq(CALLBACK_QUEUE), messageCaptor.capture());
        
        PagwMessage capturedMessage = messageCaptor.getValue();
        assertTrue(capturedMessage.getIsAsyncResponse());
        assertEquals("CARELON-999", capturedMessage.getExternalReferenceId());
    }

    @Test
    void shouldHandleS3FetchFailure() {
        // Given
        String pagwId = "PAGW-12345";
        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("canonical/PAGW-12345.json")
                .targetSystem("CLAIMS_PRO")
                .build();

        when(s3Service.getObject(anyString(), anyString()))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(RuntimeException.class, () ->
                listener.handleMessage(JsonUtils.toJson(message), pagwId)
        );

        verify(trackerService).updateStatus(pagwId, "SUBMITTING", "api-connector");
        verify(trackerService).updateError(eq(pagwId), eq("API_CONNECTOR_ERROR"),
                anyString(), eq("api-connector"));
    }

    @Test
    void shouldHandleExternalApiFailure() {
        // Given
        String pagwId = "PAGW-12345";
        String convertedPayload = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("canonical/PAGW-12345.json")
                .targetSystem("CLAIMS_PRO")
                .build();

        when(s3Service.getObject(anyString(), anyString())).thenReturn(convertedPayload);
        when(externalApiClient.submitToExternalSystem(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("API connection failed"));

        // When/Then
        assertThrows(RuntimeException.class, () ->
                listener.handleMessage(JsonUtils.toJson(message), pagwId)
        );

        verify(trackerService).updateStatus(pagwId, "SUBMITTING", "api-connector");
        verify(trackerService).updateError(eq(pagwId), eq("API_CONNECTOR_ERROR"),
                anyString(), eq("api-connector"));
    }

    @Test
    void shouldWriteCorrectMessageToCallbackQueue() {
        // Given
        String pagwId = "PAGW-12345";
        String convertedPayload = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .messageId("msg-123")
                .pagwId(pagwId)
                .schemaVersion("1.0")
                .stage("API_CONNECTOR")
                .tenant("ANTHEM")
                .payloadBucket("pagw-request-dev")
                .payloadKey("canonical/PAGW-12345.json")
                .targetSystem("CLAIMS_PRO")
                .metadata(Map.of("priority", "normal"))
                .createdAt(Instant.now())
                .build();

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setExternalId("EXT-12345");
        apiResponse.setStatus("SUBMITTED");
        apiResponse.setAsync(false);
        apiResponse.setHttpStatus(200);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(convertedPayload);
        when(externalApiClient.submitToExternalSystem(anyString(), anyString(), any())).thenReturn(apiResponse);

        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);

        // When
        listener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(outboxService).writeOutbox(eq(CALLBACK_QUEUE), messageCaptor.capture());

        PagwMessage capturedMessage = messageCaptor.getValue();
        assertEquals(pagwId, capturedMessage.getPagwId());
        assertEquals("CALLBACK_HANDLER", capturedMessage.getStage());
        assertEquals("ANTHEM", capturedMessage.getTenant());
        assertEquals("CLAIMS_PRO", capturedMessage.getTargetSystem());
        assertEquals("EXT-12345", capturedMessage.getExternalReferenceId());
        assertEquals("SUBMITTED", capturedMessage.getApiResponseStatus());
        assertFalse(capturedMessage.getIsAsyncResponse());
        assertTrue(capturedMessage.getPayloadKey().contains("payer_raw.json"));
    }

    @Test
    void shouldStoreApiResponseInS3() {
        // Given
        String pagwId = "PAGW-12345";
        String convertedPayload = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("canonical/PAGW-12345.json")
                .targetSystem("CLAIMS_PRO")
                .build();

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setExternalId("EXT-12345");
        apiResponse.setStatus("SUBMITTED");
        apiResponse.setAsync(false);
        apiResponse.setHttpStatus(200);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(convertedPayload);
        when(externalApiClient.submitToExternalSystem(anyString(), anyString(), any())).thenReturn(apiResponse);

        ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);

        // When
        listener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(s3Service).putObject(bucketCaptor.capture(), keyCaptor.capture(), dataCaptor.capture());

        assertEquals("pagw-request-dev", bucketCaptor.getValue());
        assertTrue(keyCaptor.getValue().contains("payer_raw.json"));
        assertTrue(keyCaptor.getValue().contains(pagwId));
        assertNotNull(dataCaptor.getValue());
    }

    @Test
    void shouldUseCustomCallbackQueue() {
        // Given
        String customQueue = "custom-callback-queue";
        ApiConnectorListener customListener = new ApiConnectorListener(
                externalApiClient,
                s3Service,
                trackerService,
                eventTrackerService,
                outboxService,
                customQueue
        );

        String pagwId = "PAGW-12345";
        String convertedPayload = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("canonical/PAGW-12345.json")
                .targetSystem("CLAIMS_PRO")
                .build();

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setExternalId("EXT-12345");
        apiResponse.setStatus("SUBMITTED");
        apiResponse.setAsync(false);
        apiResponse.setHttpStatus(200);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(convertedPayload);
        when(externalApiClient.submitToExternalSystem(anyString(), anyString(), any())).thenReturn(apiResponse);

        // When
        customListener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(outboxService).writeOutbox(eq(customQueue), any(PagwMessage.class));
    }
}
