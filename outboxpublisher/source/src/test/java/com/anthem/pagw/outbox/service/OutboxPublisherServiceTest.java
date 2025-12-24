package com.anthem.pagw.outbox.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.OutboxEntry;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.SqsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutboxPublisherService.
 * Tests cover scheduled publishing, manual trigger, and statistics.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxService outboxService;

    @Mock
    private SqsService sqsService;

    @Mock
    private PagwProperties properties;

    private OutboxPublisherService publisherService;

    @BeforeEach
    void setUp() {
        // Setup basic mocking structure
        publisherService = new OutboxPublisherService(outboxService, sqsService, properties);
    }

    @Test
    void publishOutboxEntries_shouldHandleEmptyOutbox() {
        // Given
        when(properties.getOutbox()).thenReturn(mock(PagwProperties.Outbox.class));
        when(properties.getOutbox().getBatchSize()).thenReturn(50);
        when(outboxService.fetchUnpublished(50)).thenReturn(Collections.emptyList());

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> publisherService.publishOutboxEntries());
        verify(sqsService, never()).sendMessage(anyString(), any());
    }

    @Test
    void publishNow_shouldHandleEmptyOutbox() {
        // Given
        when(properties.getOutbox()).thenReturn(mock(PagwProperties.Outbox.class));
        when(properties.getOutbox().getBatchSize()).thenReturn(50);
        when(outboxService.fetchUnpublished(50)).thenReturn(Collections.emptyList());

        // When
        OutboxPublisherService.PublishResult result = publisherService.publishNow();

        // Then
        assertNotNull(result);
        assertEquals(0, result.published());
        assertEquals(0, result.failed());
    }

    @Test
    void getStats_shouldReturnStatistics() {
        // Given
        when(properties.getOutbox()).thenReturn(mock(PagwProperties.Outbox.class));
        when(properties.getOutbox().getMaxRetries()).thenReturn(5);
        when(outboxService.getPendingCount()).thenReturn(100L);
        when(outboxService.getStuckEntriesCount(5)).thenReturn(3L);

        // When
        OutboxPublisherService.OutboxStats stats = publisherService.getStats();

        // Then
        assertNotNull(stats);
        assertEquals(100L, stats.unpublished());
        assertEquals(3L, stats.stuck());
    }

    @Test
    void getStats_shouldHandleZeroCounts() {
        // Given
        when(properties.getOutbox()).thenReturn(mock(PagwProperties.Outbox.class));
        when(properties.getOutbox().getMaxRetries()).thenReturn(5);
        when(outboxService.getPendingCount()).thenReturn(0L);
        when(outboxService.getStuckEntriesCount(5)).thenReturn(0L);

        // When
        OutboxPublisherService.OutboxStats stats = publisherService.getStats();

        // Then
        assertEquals(0L, stats.unpublished());
        assertEquals(0L, stats.stuck());
    }

    @Test
    void service_shouldBeInstantiable() {
        // Then
        assertNotNull(publisherService);
    }
}
