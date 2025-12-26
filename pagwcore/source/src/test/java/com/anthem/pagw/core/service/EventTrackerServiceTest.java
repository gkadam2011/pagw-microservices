package com.anthem.pagw.core.service;

import com.anthem.pagw.core.model.EventTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for EventTrackerService.
 * 
 * Tests event tracking functionality including:
 * - Stage start/complete/error logging
 * - Retry attempt tracking
 * - Sequence number generation
 * - Timeline retrieval
 * - Failed event queries
 */
class EventTrackerServiceTest {
    
    private JdbcTemplate jdbcTemplate;
    private EventTrackerService eventTrackerService;
    
    private static final String PAGW_ID = "PAGW-20251225-00001-TEST1234";
    private static final String TENANT = "elevance";
    private static final String STAGE = "PARSING";
    
    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        eventTrackerService = new EventTrackerService(jdbcTemplate);
    }
    
    @Test
    void testLogStageStart() {
        // Mock sequence number query
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(1L);
        
        // Execute
        long sequenceNo = eventTrackerService.logStageStart(
                PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_START);
        
        // Verify
        assertEquals(1L, sequenceNo);
        
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        
        // One update call (sequence number query is queryForObject, not update)
        verify(jdbcTemplate, times(1)).update(sqlCaptor.capture(), argsCaptor.capture());
        
        // Verify insert SQL contains expected fields
        String insertSql = sqlCaptor.getValue();
        assertTrue(insertSql.contains("INSERT INTO pagw.event_tracker"));
        assertTrue(insertSql.contains("sequence_no"));
        assertTrue(insertSql.contains("STARTED"));
        
        // Verify parameters
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals(TENANT, insertArgs[0]);
        assertEquals(PAGW_ID, insertArgs[1]);
        assertEquals(EventTracker.EVENT_PARSE_START, insertArgs[2]);
    }
    
    @Test
    void testLogStageStartWithMetadata() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(2L);
        
        String metadata = "{\"s3Key\":\"test.json\"}";
        
        long sequenceNo = eventTrackerService.logStageStart(
                PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_START, metadata);
        
        assertEquals(2L, sequenceNo);
        
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).update(anyString(), argsCaptor.capture());
        
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals(metadata, insertArgs[5]); // Last parameter should be metadata
    }
    
    @Test
    void testLogStageStart_WithNullTenant_ShouldUseUnknown() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(1L);
        
        // Call with null tenant
        long sequenceNo = eventTrackerService.logStageStart(
                PAGW_ID, null, STAGE, EventTracker.EVENT_PARSE_START, null);
        
        assertEquals(1L, sequenceNo);
        
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).update(anyString(), argsCaptor.capture());
        
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals("UNKNOWN", insertArgs[0], "Null tenant should be replaced with UNKNOWN");
    }
    
    @Test
    void testLogStageStart_WithBlankTenant_ShouldUseUnknown() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(1L);
        
        // Call with blank tenant
        long sequenceNo = eventTrackerService.logStageStart(
                PAGW_ID, "   ", STAGE, EventTracker.EVENT_PARSE_START, null);
        
        assertEquals(1L, sequenceNo);
        
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).update(anyString(), argsCaptor.capture());
        
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals("UNKNOWN", insertArgs[0], "Blank tenant should be replaced with UNKNOWN");
    }

    @Test
    void testLogStageComplete() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(3L);
        
        long durationMs = 1250L;
        
        long sequenceNo = eventTrackerService.logStageComplete(
                PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_OK, durationMs);
        
        assertEquals(3L, sequenceNo);
        
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).update(anyString(), argsCaptor.capture());
        
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals(durationMs, insertArgs[5]); // duration_ms parameter
    }
    
    @Test
    void testLogStageCompleteWithMetadata() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(4L);
        
        String metadata = "{\"resourceCount\":10}";
        long durationMs = 500L;
        
        long sequenceNo = eventTrackerService.logStageComplete(
                PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_OK, durationMs, metadata);
        
        assertEquals(4L, sequenceNo);
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }
    
    @Test
    void testLogStageError() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(5L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString(), anyString(), anyString()))
                .thenReturn(0);
        
        String errorCode = "PARSE_ERROR";
        String errorMessage = "Invalid FHIR bundle structure";
        
        long sequenceNo = eventTrackerService.logStageError(
                PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_FAIL, errorCode, errorMessage);
        
        assertEquals(5L, sequenceNo);
        
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).update(anyString(), argsCaptor.capture());
        
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals(errorCode, insertArgs[8]);
        assertEquals(errorMessage, insertArgs[9]);
        assertFalse((Boolean) insertArgs[6]); // retryable = false
    }
    
    @Test
    void testLogStageErrorWithRetry() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(6L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString(), anyString(), anyString()))
                .thenReturn(1); // Second attempt
        
        Instant nextRetry = Instant.now().plus(5, ChronoUnit.MINUTES);
        
        long sequenceNo = eventTrackerService.logStageError(
                PAGW_ID, TENANT, "SUBMISSION", EventTracker.EVENT_API_CON_TIMEOUT,
                "TIMEOUT", "Downstream service timeout", true, nextRetry);
        
        assertEquals(6L, sequenceNo);
        
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(1)).update(anyString(), argsCaptor.capture());
        
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals(1, insertArgs[5]); // attempt = 1
        assertTrue((Boolean) insertArgs[6]); // retryable = true
        assertNotNull(insertArgs[7]); // next_retry_at
    }
    
    @Test
    void testLogRetryAttempt() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(7L);
        
        int attempt = 2;
        
        long sequenceNo = eventTrackerService.logRetryAttempt(
                PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_START, attempt);
        
        assertEquals(7L, sequenceNo);
        
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        
        verify(jdbcTemplate, times(1)).update(sqlCaptor.capture(), argsCaptor.capture());
        
        String insertSql = sqlCaptor.getValue();
        assertTrue(insertSql.contains("RETRY"));
        
        Object[] insertArgs = argsCaptor.getValue();
        assertEquals(attempt, insertArgs[5]);
    }
    
    @Test
    void testGetTimeline() {
        // Mock timeline data
        List<EventTracker> mockTimeline = Arrays.asList(
                createMockEvent(1L, EventTracker.EVENT_REQUEST_RECEIVED, "STARTED"),
                createMockEvent(2L, EventTracker.EVENT_PARSE_START, "STARTED"),
                createMockEvent(3L, EventTracker.EVENT_PARSE_OK, "SUCCESS")
        );
        
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(PAGW_ID)))
                .thenReturn(mockTimeline);
        
        // Execute
        List<EventTracker> timeline = eventTrackerService.getTimeline(PAGW_ID);
        
        // Verify
        assertNotNull(timeline);
        assertEquals(3, timeline.size());
        assertEquals(1L, timeline.get(0).getSequenceNo());
        assertEquals(EventTracker.EVENT_REQUEST_RECEIVED, timeline.get(0).getEventType());
        assertEquals(3L, timeline.get(2).getSequenceNo());
        assertEquals(EventTracker.EVENT_PARSE_OK, timeline.get(2).getEventType());
        
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(PAGW_ID));
    }
    
    @Test
    void testGetFailedRetryableEvents() {
        String tenant = "elevance";
        int minutesAgo = 60;
        
        // Mock failed retryable events
        List<EventTracker> mockEvents = Arrays.asList(
                createFailedRetryableEvent(PAGW_ID, EventTracker.EVENT_API_CON_TIMEOUT),
                createFailedRetryableEvent("PAGW-20251225-00002-TEST5678", EventTracker.EVENT_API_CON_FAIL)
        );
        
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(tenant)))
                .thenReturn(mockEvents);
        
        // Execute
        List<EventTracker> events = eventTrackerService.getFailedRetryableEvents(tenant, minutesAgo);
        
        // Verify
        assertNotNull(events);
        assertEquals(2, events.size());
        assertTrue(events.get(0).getRetryable());
        assertTrue(events.get(0).isFailure());
        
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(tenant));
    }
    
    @Test
    void testUpdateWorkerId() {
        long eventId = 12345L;
        String workerId = "pod-pasorchestrator-abc123";
        
        eventTrackerService.updateWorkerId(eventId, workerId);
        
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("UPDATE pagw.event_tracker"));
        assertTrue(sql.contains("worker_id"));
        
        Object[] args = argsCaptor.getValue();
        assertEquals(workerId, args[0]);
        assertEquals(eventId, args[1]);
    }
    
    @Test
    void testSequenceNumberIncrement() {
        // Simulate multiple events for same request
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(1L, 2L, 3L);
        
        long seq1 = eventTrackerService.logStageStart(PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_START);
        long seq2 = eventTrackerService.logStageComplete(PAGW_ID, TENANT, STAGE, EventTracker.EVENT_PARSE_OK, 100L);
        long seq3 = eventTrackerService.logStageStart(PAGW_ID, TENANT, "VALIDATION", EventTracker.EVENT_VAL_START);
        
        assertEquals(1L, seq1);
        assertEquals(2L, seq2);
        assertEquals(3L, seq3);
        
        // Verify sequence query was called 3 times
        verify(jdbcTemplate, times(3)).queryForObject(
                contains("MAX(sequence_no)"), 
                eq(Long.class), 
                eq(PAGW_ID)
        );
    }
    
    @Test
    void testEventTrackerIsSuccess() {
        EventTracker event = new EventTracker();
        
        event.setStatus(EventTracker.STATUS_SUCCESS);
        assertTrue(event.isSuccess());
        
        event.setStatus(EventTracker.STATUS_FAILURE);
        assertFalse(event.isSuccess());
        
        event.setStatus(EventTracker.STATUS_SUCCESS);
        event.setEventType(EventTracker.EVENT_PARSE_OK);
        assertTrue(event.isSuccess());
        
        event.setEventType(EventTracker.EVENT_PARSE_FAIL);
        assertTrue(event.isSuccess()); // Status takes precedence
    }
    
    @Test
    void testEventTrackerIsFailure() {
        EventTracker event = new EventTracker();
        
        event.setStatus(EventTracker.STATUS_FAILURE);
        assertTrue(event.isFailure());
        
        event.setStatus(EventTracker.STATUS_SUCCESS);
        assertFalse(event.isFailure());
        
        event.setStatus(null);
        event.setEventType(EventTracker.EVENT_PARSE_FAIL);
        assertTrue(event.isFailure());
        
        event.setEventType(EventTracker.EVENT_PARSE_OK);
        assertFalse(event.isFailure());
    }
    
    @Test
    void testEventTrackerCanRetry() {
        EventTracker event = new EventTracker();
        
        // Not retryable
        event.setRetryable(false);
        assertFalse(event.canRetry());
        
        // Retryable with no next retry time
        event.setRetryable(true);
        event.setNextRetryAt(null);
        assertTrue(event.canRetry());
        
        // Retryable with past retry time
        event.setNextRetryAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        assertTrue(event.canRetry());
        
        // Retryable with future retry time
        event.setNextRetryAt(Instant.now().plus(5, ChronoUnit.MINUTES));
        assertFalse(event.canRetry());
    }
    
    // Helper methods
    
    private EventTracker createMockEvent(Long sequenceNo, String eventType, String status) {
        EventTracker event = new EventTracker();
        event.setId(sequenceNo * 100);
        event.setSequenceNo(sequenceNo);
        event.setPagwId(PAGW_ID);
        event.setTenant(TENANT);
        event.setStage(STAGE);
        event.setEventType(eventType);
        event.setStatus(status);
        event.setAttempt(0);
        event.setRetryable(false);
        event.setCreatedAt(Instant.now());
        return event;
    }
    
    private EventTracker createFailedRetryableEvent(String pagwId, String eventType) {
        EventTracker event = new EventTracker();
        event.setId(System.currentTimeMillis());
        event.setPagwId(pagwId);
        event.setTenant(TENANT);
        event.setStage("SUBMISSION");
        event.setEventType(eventType);
        event.setStatus(EventTracker.STATUS_FAILURE);
        event.setRetryable(true);
        event.setAttempt(1);
        event.setErrorCode("TIMEOUT");
        event.setErrorMessage("Downstream timeout");
        event.setCreatedAt(Instant.now());
        return event;
    }
}
