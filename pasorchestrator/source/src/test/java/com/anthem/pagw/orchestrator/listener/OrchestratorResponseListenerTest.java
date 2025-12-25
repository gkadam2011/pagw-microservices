package com.anthem.pagw.orchestrator.listener;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrchestratorResponseListener.
 * Tests routing logic for async workflow messages.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorResponseListenerTest {

    @Mock
    private OutboxService outboxService;

    @Mock
    private RequestTrackerService trackerService;

    private OrchestratorResponseListener listener;

    // AWS SQS queue names (must match actual queue names in AWS)
    private static final String REQUEST_PARSER_QUEUE = "dev-PAGW-pagw-request-parser-queue.fifo";
    private static final String BUSINESS_VALIDATOR_QUEUE = "dev-PAGW-pagw-business-validator-queue.fifo";
    private static final String REQUEST_ENRICHER_QUEUE = "dev-PAGW-pagw-request-enricher-queue.fifo";
    private static final String REQUEST_CONVERTER_QUEUE = "dev-PAGW-pagw-request-converter-queue.fifo";
    private static final String API_CONNECTORS_QUEUE = "dev-PAGW-pagw-api-connectors-queue.fifo";

    @BeforeEach
    void setUp() {
        listener = new OrchestratorResponseListener(
                outboxService,
                trackerService
        );
    }

    @Test
    void handleMessage_shouldRouteToRequestParserQueue() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00001-TEST")
                .stage("REQUEST_PARSER")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, "PAGW-20251224-00001-TEST");

        // Then
        verify(trackerService).updateStatus("PAGW-20251224-00001-TEST", "ROUTING_TO_REQUEST_PARSER", "orchestrator");
        
        ArgumentCaptor<String> queueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);
        verify(outboxService).writeOutbox(queueCaptor.capture(), messageCaptor.capture());
        
        assertEquals(REQUEST_PARSER_QUEUE, queueCaptor.getValue());
        assertEquals("PAGW-20251224-00001-TEST", messageCaptor.getValue().getPagwId());
        assertEquals("REQUEST_PARSER", messageCaptor.getValue().getStage());
    }

    @Test
    void handleMessage_shouldRouteToBusinessValidatorQueue() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00002-TEST")
                .stage("BUSINESS_VALIDATOR")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, "PAGW-20251224-00002-TEST");

        // Then
        verify(trackerService).updateStatus("PAGW-20251224-00002-TEST", "ROUTING_TO_BUSINESS_VALIDATOR", "orchestrator");
        
        ArgumentCaptor<String> queueCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).writeOutbox(queueCaptor.capture(), any(PagwMessage.class));
        
        assertEquals(BUSINESS_VALIDATOR_QUEUE, queueCaptor.getValue());
    }

    @Test
    void handleMessage_shouldRouteToRequestEnricherQueue() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00003-TEST")
                .stage("REQUEST_ENRICHER")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, "PAGW-20251224-00003-TEST");

        // Then
        ArgumentCaptor<String> queueCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).writeOutbox(queueCaptor.capture(), any(PagwMessage.class));
        
        assertEquals(REQUEST_ENRICHER_QUEUE, queueCaptor.getValue());
    }

    @Test
    void handleMessage_shouldRouteToRequestConverterQueue() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00004-TEST")
                .stage("REQUEST_CONVERTER")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, "PAGW-20251224-00004-TEST");

        // Then
        ArgumentCaptor<String> queueCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).writeOutbox(queueCaptor.capture(), any(PagwMessage.class));
        
        assertEquals(REQUEST_CONVERTER_QUEUE, queueCaptor.getValue());
    }

    @Test
    void handleMessage_shouldRouteToApiConnectorQueue() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00005-TEST")
                .stage("API_CONNECTOR")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, "PAGW-20251224-00005-TEST");

        // Then
        ArgumentCaptor<String> queueCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).writeOutbox(queueCaptor.capture(), any(PagwMessage.class));
        
        assertEquals(API_CONNECTORS_QUEUE, queueCaptor.getValue());
    }

    @Test
    void handleMessage_shouldHandleUnknownStage() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00006-TEST")
                .stage("UNKNOWN_STAGE")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, "PAGW-20251224-00006-TEST");

        // Then
        verify(trackerService).updateError(
                eq("PAGW-20251224-00006-TEST"),
                eq("ROUTING_ERROR"),
                eq("Unknown stage: UNKNOWN_STAGE"),
                eq("orchestrator")
        );
        verify(outboxService, never()).writeOutbox(anyString(), any(PagwMessage.class));
    }

    @Test
    void handleMessage_shouldHandleInvalidJson() {
        // Given
        String invalidJson = "{invalid json}";

        // When/Then
        assertThrows(RuntimeException.class, () -> 
                listener.handleMessage(invalidJson, null)
        );
        
        verify(outboxService, never()).writeOutbox(anyString(), any(PagwMessage.class));
    }

    @Test
    void handleMessage_shouldUpdateTrackerOnError() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00007-TEST")
                .stage("REQUEST_PARSER")
                .build();
        String messageBody = JsonUtils.toJson(message);
        
        doThrow(new RuntimeException("Outbox write failed"))
                .when(outboxService).writeOutbox(anyString(), any(PagwMessage.class));

        // When/Then
        assertThrows(RuntimeException.class, () -> 
                listener.handleMessage(messageBody, "PAGW-20251224-00007-TEST")
        );
        
        verify(trackerService).updateError(
                eq("PAGW-20251224-00007-TEST"),
                eq("ORCHESTRATOR_ROUTING_ERROR"),
                contains("Outbox write failed"),
                eq("orchestrator")
        );
    }

    @Test
    void handleMessage_shouldExtractPagwIdFromMessage() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00008-TEST")
                .stage("REQUEST_PARSER")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, null); // No header provided

        // Then
        verify(trackerService).updateStatus("PAGW-20251224-00008-TEST", "ROUTING_TO_REQUEST_PARSER", "orchestrator");
        verify(outboxService).writeOutbox(anyString(), any(PagwMessage.class));
    }

    @Test
    void handleMessage_shouldPreserveMessageMetadata() {
        // Given
        PagwMessage message = PagwMessage.builder()
                .pagwId("PAGW-20251224-00009-TEST")
                .stage("BUSINESS_VALIDATOR")
                .tenant("ANTHEM")
                .payloadBucket("test-bucket")
                .payloadKey("test-key")
                .build();
        String messageBody = JsonUtils.toJson(message);

        // When
        listener.handleMessage(messageBody, "PAGW-20251224-00009-TEST");

        // Then
        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);
        verify(outboxService).writeOutbox(anyString(), messageCaptor.capture());
        
        PagwMessage routedMessage = messageCaptor.getValue();
        assertEquals("PAGW-20251224-00009-TEST", routedMessage.getPagwId());
        assertEquals("BUSINESS_VALIDATOR", routedMessage.getStage());
        assertEquals("ANTHEM", routedMessage.getTenant());
        assertEquals("test-bucket", routedMessage.getPayloadBucket());
        assertEquals("test-key", routedMessage.getPayloadKey());
    }
}
