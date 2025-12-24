package com.anthem.pagw.outbox.controller;

import com.anthem.pagw.outbox.service.OutboxPublisherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive unit tests for OutboxController.
 * Tests REST endpoints for statistics, manual triggering, and health checks.
 */
@WebMvcTest(OutboxController.class)
class OutboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OutboxPublisherService publisherService;

    // ============================================================================
    // GET /outbox/stats - Statistics Endpoint Tests
    // ============================================================================

    @Test
    void getStats_shouldReturnStatistics() throws Exception {
        // Given
        OutboxPublisherService.OutboxStats stats = new OutboxPublisherService.OutboxStats(100L, 5L);
        when(publisherService.getStats()).thenReturn(stats);

        // When & Then
        mockMvc.perform(get("/outbox/stats")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpublished").value(100))
                .andExpect(jsonPath("$.stuck").value(5));

        verify(publisherService).getStats();
    }

    @Test
    void getStats_shouldReturnZeroCounts() throws Exception {
        // Given
        OutboxPublisherService.OutboxStats stats = new OutboxPublisherService.OutboxStats(0L, 0L);
        when(publisherService.getStats()).thenReturn(stats);

        // When & Then
        mockMvc.perform(get("/outbox/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpublished").value(0))
                .andExpect(jsonPath("$.stuck").value(0));
    }

    @Test
    void getStats_shouldHandleServiceError() throws Exception {
        // Given
        when(publisherService.getStats()).thenThrow(new RuntimeException("Database error"));

        // When & Then
        mockMvc.perform(get("/outbox/stats"))
                .andExpect(status().is5xxServerError());
    }

    // ============================================================================
    // POST /outbox/publish - Manual Trigger Tests
    // ============================================================================

    @Test
    void triggerPublish_shouldReturnPublishResult() throws Exception {
        // Given
        OutboxPublisherService.PublishResult result = new OutboxPublisherService.PublishResult(10, 2);
        when(publisherService.publishNow()).thenReturn(result);

        // When & Then
        mockMvc.perform(post("/outbox/publish")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(10))
                .andExpect(jsonPath("$.failed").value(2));

        verify(publisherService).publishNow();
    }

    @Test
    void triggerPublish_shouldHandleNoMessages() throws Exception {
        // Given
        OutboxPublisherService.PublishResult result = new OutboxPublisherService.PublishResult(0, 0);
        when(publisherService.publishNow()).thenReturn(result);

        // When & Then
        mockMvc.perform(post("/outbox/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(0))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    void triggerPublish_shouldHandleAllFailures() throws Exception {
        // Given
        OutboxPublisherService.PublishResult result = new OutboxPublisherService.PublishResult(0, 5);
        when(publisherService.publishNow()).thenReturn(result);

        // When & Then
        mockMvc.perform(post("/outbox/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(0))
                .andExpect(jsonPath("$.failed").value(5));
    }

    @Test
    void triggerPublish_shouldHandleServiceError() throws Exception {
        // Given
        when(publisherService.publishNow()).thenThrow(new RuntimeException("SQS unavailable"));

        // When & Then
        mockMvc.perform(post("/outbox/publish"))
                .andExpect(status().is5xxServerError());
    }

    // ============================================================================
    // GET /outbox/health - Health Check Tests
    // ============================================================================

    @Test
    void health_shouldReturnHealthyStatus() throws Exception {
        // When & Then
        mockMvc.perform(get("/outbox/health")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void health_shouldNotCallPublisherService() throws Exception {
        // When
        mockMvc.perform(get("/outbox/health"));

        // Then
        verifyNoInteractions(publisherService);
    }

    // ============================================================================
    // Content Type Tests
    // ============================================================================

    @Test
    void getStats_shouldAcceptJsonContentType() throws Exception {
        // Given
        OutboxPublisherService.OutboxStats stats = new OutboxPublisherService.OutboxStats(50L, 1L);
        when(publisherService.getStats()).thenReturn(stats);

        // When & Then
        mockMvc.perform(get("/outbox/stats")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void triggerPublish_shouldAcceptJsonContentType() throws Exception {
        // Given
        OutboxPublisherService.PublishResult result = new OutboxPublisherService.PublishResult(5, 0);
        when(publisherService.publishNow()).thenReturn(result);

        // When & Then
        mockMvc.perform(post("/outbox/publish")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    // ============================================================================
    // HTTP Method Tests
    // ============================================================================

    @Test
    void getStats_shouldNotAllowPost() throws Exception {
        mockMvc.perform(post("/outbox/stats"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void triggerPublish_shouldNotAllowGet() throws Exception {
        mockMvc.perform(get("/outbox/publish"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void health_shouldNotAllowPost() throws Exception {
        mockMvc.perform(post("/outbox/health"))
                .andExpect(status().isMethodNotAllowed());
    }
}
