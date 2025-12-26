package com.anthem.pagw.enricher.listener;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.enricher.model.EnrichmentResult;
import com.anthem.pagw.enricher.service.RequestEnricherService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RequestEnricherListener.
 */
@ExtendWith(MockitoExtension.class)
class RequestEnricherListenerTest {

    @Mock
    private RequestEnricherService enricherService;

    @Mock
    private S3Service s3Service;

    @Mock
    private RequestTrackerService trackerService;

    @Mock
    private EventTrackerService eventTrackerService;

    @Mock
    private OutboxService outboxService;

    private RequestEnricherListener listener;
    private static final String NEXT_QUEUE_NAME = "pagw-request-converter-queue";
    private static final String TEST_PAGW_ID = "PAGW-TEST-12345";
    private static final String TEST_BUCKET = "test-bucket";

    @BeforeEach
    void setUp() {
        listener = new RequestEnricherListener(
                enricherService,
                s3Service,
                trackerService,
                eventTrackerService,
                outboxService,
                NEXT_QUEUE_NAME
        );
    }

    @Test
    void handleMessage_shouldEnrichAndSendToNextQueue() {
        // Given
        PagwMessage inputMessage = createTestMessage();
        String messageJson = JsonUtils.toJson(inputMessage);
        String validatedData = "{\"patientData\":{\"memberId\":\"M123\"}}";
        
        String enrichedDataJson = "{\"patientData\":{\"memberId\":\"M123\"},\"eligibilityData\":{\"status\":\"ACTIVE\"}}";
        ObjectNode enrichedData = (ObjectNode) JsonUtils.parseJson(enrichedDataJson);
        EnrichmentResult enrichmentResult = new EnrichmentResult();
        enrichmentResult.setEnrichedData(enrichedData);
        enrichmentResult.setSourcesUsed(List.of("ELIGIBILITY_SERVICE"));
        enrichmentResult.setSuccess(true);

        when(s3Service.getObject(TEST_BUCKET, inputMessage.getPayloadKey()))
                .thenReturn(validatedData);
        when(enricherService.enrich(validatedData, inputMessage))
                .thenReturn(enrichmentResult);

        // When
        listener.handleMessage(messageJson, TEST_PAGW_ID);

        // Then
        verify(trackerService).updateStatus(TEST_PAGW_ID, "ENRICHING", "request-enricher");
        verify(s3Service).getObject(TEST_BUCKET, inputMessage.getPayloadKey());
        verify(enricherService).enrich(validatedData, inputMessage);
        
        ArgumentCaptor<String> s3KeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> s3DataCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service).putObject(eq(TEST_BUCKET), s3KeyCaptor.capture(), s3DataCaptor.capture());
        
        String savedKey = s3KeyCaptor.getValue();
        assertThat(savedKey).isEqualTo(PagwProperties.S3Paths.enriched(TEST_PAGW_ID));
        
        ArgumentCaptor<PagwMessage> messageCaptor = ArgumentCaptor.forClass(PagwMessage.class);
        verify(outboxService).writeOutbox(eq(NEXT_QUEUE_NAME), messageCaptor.capture());
        
        PagwMessage sentMessage = messageCaptor.getValue();
        assertThat(sentMessage.getPagwId()).isEqualTo(TEST_PAGW_ID);
        assertThat(sentMessage.getStage()).isEqualTo("CANONICAL_MAPPER");
        assertThat(sentMessage.getPayloadKey()).isEqualTo(savedKey);
        assertThat(sentMessage.getEnrichmentSources()).containsExactly("ELIGIBILITY_SERVICE");
        
        verify(trackerService).updateStatus(TEST_PAGW_ID, "ENRICHED", "request-enricher");
    }

    @Test
    void handleMessage_shouldHandleEnrichmentFailure() {
        // Given
        PagwMessage inputMessage = createTestMessage();
        String messageJson = JsonUtils.toJson(inputMessage);
        String validatedData = "{\"patientData\":{}}";

        when(s3Service.getObject(TEST_BUCKET, inputMessage.getPayloadKey()))
                .thenReturn(validatedData);
        when(enricherService.enrich(validatedData, inputMessage))
                .thenThrow(new RuntimeException("Enrichment service unavailable"));

        // When/Then
        assertThatThrownBy(() -> listener.handleMessage(messageJson, TEST_PAGW_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process message");

        verify(trackerService).updateStatus(TEST_PAGW_ID, "ENRICHING", "request-enricher");
        verify(trackerService).updateStatus(
                eq(TEST_PAGW_ID), 
                eq("ENRICHMENT_ERROR"), 
                eq("request-enricher"),
                eq("EXCEPTION"),
                contains("Enrichment service unavailable")
        );
        verify(outboxService, never()).writeOutbox(anyString(), any());
    }

    @Test
    void handleMessage_shouldHandleS3Failure() {
        // Given
        PagwMessage inputMessage = createTestMessage();
        String messageJson = JsonUtils.toJson(inputMessage);

        when(s3Service.getObject(TEST_BUCKET, inputMessage.getPayloadKey()))
                .thenThrow(new RuntimeException("S3 access denied"));

        // When/Then
        assertThatThrownBy(() -> listener.handleMessage(messageJson, TEST_PAGW_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to process message");

        verify(trackerService).updateStatus(TEST_PAGW_ID, "ENRICHING", "request-enricher");
        verify(enricherService, never()).enrich(anyString(), any());
        verify(outboxService, never()).writeOutbox(anyString(), any());
    }

    @Test
    void handleMessage_shouldHandleInvalidMessageFormat() {
        // Given
        String invalidJson = "{invalid-json}";

        // When/Then
        assertThatThrownBy(() -> listener.handleMessage(invalidJson, null))
                .isInstanceOf(RuntimeException.class);

        verify(trackerService, never()).updateStatus(anyString(), anyString(), anyString());
    }

    @Test
    void handleMessage_shouldUseConfiguredNextQueueName() {
        // Given
        String customQueueName = "custom-queue-name";
        RequestEnricherListener customListener = new RequestEnricherListener(
                enricherService,
                s3Service,
                trackerService,
                eventTrackerService,
                outboxService,
                customQueueName
        );

        PagwMessage inputMessage = createTestMessage();
        String messageJson = JsonUtils.toJson(inputMessage);
        String validatedData = "{}";
        
        EnrichmentResult enrichmentResult = new EnrichmentResult();
        enrichmentResult.setEnrichedData((ObjectNode) JsonUtils.parseJson("{}"));
        enrichmentResult.setSourcesUsed(List.of());
        enrichmentResult.setSuccess(true);

        when(s3Service.getObject(anyString(), anyString())).thenReturn(validatedData);
        when(enricherService.enrich(anyString(), any())).thenReturn(enrichmentResult);

        // When
        customListener.handleMessage(messageJson, TEST_PAGW_ID);

        // Then
        verify(outboxService).writeOutbox(eq(customQueueName), any(PagwMessage.class));
    }

    private PagwMessage createTestMessage() {
        return PagwMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .pagwId(TEST_PAGW_ID)
                .schemaVersion("1.0")
                .stage("VALIDATION_COMPLETE")
                .tenant("ANTHEM")
                .payloadBucket(TEST_BUCKET)
                .payloadKey("requests/" + TEST_PAGW_ID + "/validated.json")
                .metadata(null)
                .createdAt(Instant.now())
                .build();
    }
}
