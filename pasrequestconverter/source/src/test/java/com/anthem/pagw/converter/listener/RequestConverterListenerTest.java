package com.anthem.pagw.converter.listener;

import com.anthem.pagw.converter.model.ConversionResult;
import com.anthem.pagw.converter.model.ConvertedPayload;
import com.anthem.pagw.converter.service.RequestConverterService;
import com.anthem.pagw.core.model.PagwMessage;
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
 * Unit tests for RequestConverterListener.
 */
@ExtendWith(MockitoExtension.class)
class RequestConverterListenerTest {

    @Mock
    private RequestConverterService converterService;

    @Mock
    private S3Service s3Service;

    @Mock
    private RequestTrackerService trackerService;

    @Mock
    private OutboxService outboxService;

    private RequestConverterListener listener;

    private static final String NEXT_QUEUE = "pagw-api-connector-queue";

    @BeforeEach
    void setUp() {
        listener = new RequestConverterListener(
                converterService,
                s3Service,
                trackerService,
                outboxService,
                NEXT_QUEUE
        );
    }

    @Test
    void shouldConvertMessageSuccessfully() {
        // Given
        String pagwId = "PAGW-12345";
        String enrichedData = "{\"claimId\":\"CLM-001\",\"patientData\":{\"id\":\"P123\"}}";

        PagwMessage message = PagwMessage.builder()
                .messageId("msg-123")
                .pagwId(pagwId)
                .schemaVersion("1.0")
                .stage("REQUEST_CONVERTER")
                .tenant("ANTHEM")
                .payloadBucket("pagw-request-dev")
                .payloadKey("enriched/PAGW-12345.json")
                .metadata(Map.of("priority", "normal"))
                .createdAt(Instant.now())
                .build();

        ConvertedPayload convertedPayload = new ConvertedPayload();
        convertedPayload.setPagwId(pagwId);
        convertedPayload.setTargetSystem("CLAIMS_PRO");

        ConversionResult result = new ConversionResult();
        result.setSuccess(true);
        result.setTargetSystem("CLAIMS_PRO");
        result.setConvertedPayload(convertedPayload);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(enrichedData);
        when(converterService.convertPayload(anyString(), any())).thenReturn(result);

        // When
        listener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(trackerService).updateStatus(pagwId, "CONVERTING", "request-converter");
        verify(s3Service).getObject("pagw-request-dev", "enriched/PAGW-12345.json");
        verify(converterService).convertPayload(enrichedData, message);
        verify(s3Service).putObject(eq("pagw-request-dev"), startsWith("canonical/"), anyString());
        verify(outboxService).writeOutbox(eq(NEXT_QUEUE), any(PagwMessage.class));
        verify(trackerService).updateStatus(pagwId, "CONVERTED", "request-converter");
    }

    @Test
    void shouldHandleS3FetchFailure() {
        // Given
        String pagwId = "PAGW-12345";
        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("enriched/PAGW-12345.json")
                .build();

        when(s3Service.getObject(anyString(), anyString()))
                .thenThrow(new RuntimeException("S3 error"));

        // When/Then
        assertThrows(RuntimeException.class, () ->
                listener.handleMessage(JsonUtils.toJson(message), pagwId)
        );

        verify(trackerService).updateStatus(pagwId, "CONVERTING", "request-converter");
        verify(trackerService).updateError(eq(pagwId), eq("CONVERSION_ERROR"), 
                anyString(), eq("request-converter"));
    }

    @Test
    void shouldHandleConversionFailure() {
        // Given
        String pagwId = "PAGW-12345";
        String enrichedData = "{\"invalid\":\"data\"}";

        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("enriched/PAGW-12345.json")
                .build();

        when(s3Service.getObject(anyString(), anyString())).thenReturn(enrichedData);
        when(converterService.convertPayload(anyString(), any()))
                .thenThrow(new RuntimeException("Conversion failed"));

        // When/Then
        assertThrows(RuntimeException.class, () ->
                listener.handleMessage(JsonUtils.toJson(message), pagwId)
        );

        verify(trackerService).updateStatus(pagwId, "CONVERTING", "request-converter");
        verify(trackerService).updateError(eq(pagwId), eq("CONVERSION_ERROR"), 
                anyString(), eq("request-converter"));
    }

    @Test
    void shouldWriteCorrectMessageToOutbox() {
        // Given
        String pagwId = "PAGW-12345";
        String enrichedData = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .messageId("msg-123")
                .pagwId(pagwId)
                .schemaVersion("1.0")
                .stage("REQUEST_CONVERTER")
                .tenant("ANTHEM")
                .payloadBucket("pagw-request-dev")
                .payloadKey("enriched/PAGW-12345.json")
                .metadata(Map.of("priority", "normal"))
                .createdAt(Instant.now())
                .build();

        ConvertedPayload convertedPayload = new ConvertedPayload();
        convertedPayload.setPagwId(pagwId);
        convertedPayload.setTargetSystem("CLAIMS_PRO");

        ConversionResult result = new ConversionResult();
        result.setSuccess(true);
        result.setTargetSystem("CLAIMS_PRO");
        result.setConvertedPayload(convertedPayload);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(enrichedData);
        when(converterService.convertPayload(anyString(), any())).thenReturn(result);

        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);

        // When
        listener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(outboxService).writeOutbox(eq(NEXT_QUEUE), messageCaptor.capture());

        PagwMessage capturedMessage = messageCaptor.getValue();
        assertEquals(pagwId, capturedMessage.getPagwId());
        assertEquals("API_CONNECTOR", capturedMessage.getStage());
        assertEquals("ANTHEM", capturedMessage.getTenant());
        assertEquals("CLAIMS_PRO", capturedMessage.getTargetSystem());
        assertTrue(capturedMessage.getPayloadKey().startsWith("canonical/"));
    }

    @Test
    void shouldUseCustomQueueName() {
        // Given
        String customQueue = "custom-api-connector-queue";
        RequestConverterListener customListener = new RequestConverterListener(
                converterService,
                s3Service,
                trackerService,
                outboxService,
                customQueue
        );

        String pagwId = "PAGW-12345";
        String enrichedData = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("enriched/PAGW-12345.json")
                .build();

        ConvertedPayload convertedPayload = new ConvertedPayload();
        ConversionResult result = new ConversionResult();
        result.setSuccess(true);
        result.setTargetSystem("CLAIMS_PRO");
        result.setConvertedPayload(convertedPayload);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(enrichedData);
        when(converterService.convertPayload(anyString(), any())).thenReturn(result);

        // When
        customListener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(outboxService).writeOutbox(eq(customQueue), any(PagwMessage.class));
    }

    @Test
    void shouldStoreConvertedPayloadInS3() {
        // Given
        String pagwId = "PAGW-12345";
        String enrichedData = "{\"claimId\":\"CLM-001\"}";

        PagwMessage message = PagwMessage.builder()
                .pagwId(pagwId)
                .payloadBucket("pagw-request-dev")
                .payloadKey("enriched/PAGW-12345.json")
                .build();

        ConvertedPayload convertedPayload = new ConvertedPayload();
        convertedPayload.setPagwId(pagwId);

        ConversionResult result = new ConversionResult();
        result.setSuccess(true);
        result.setTargetSystem("CLAIMS_PRO");
        result.setConvertedPayload(convertedPayload);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(enrichedData);
        when(converterService.convertPayload(anyString(), any())).thenReturn(result);

        ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);

        // When
        listener.handleMessage(JsonUtils.toJson(message), pagwId);

        // Then
        verify(s3Service).putObject(bucketCaptor.capture(), keyCaptor.capture(), dataCaptor.capture());

        assertEquals("pagw-request-dev", bucketCaptor.getValue());
        assertTrue(keyCaptor.getValue().startsWith("canonical/"));
        assertTrue(keyCaptor.getValue().contains(pagwId));
        assertNotNull(dataCaptor.getValue());
    }
}
