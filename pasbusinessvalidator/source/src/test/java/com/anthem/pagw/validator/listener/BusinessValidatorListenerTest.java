package com.anthem.pagw.validator.listener;

import com.anthem.pagw.core.model.EventTracker;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.service.EventTrackerService;
import com.anthem.pagw.core.service.OutboxService;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.validator.model.ValidationError;
import com.anthem.pagw.validator.model.ValidationResult;
import com.anthem.pagw.validator.service.BusinessValidatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class for BusinessValidatorListener EventTrackerService integration.
 */
@ExtendWith(MockitoExtension.class)
class BusinessValidatorListenerTest {

    private static final String PAGW_ID = "PAGW-20251225-00001-TEST1234";
    private static final String TENANT = "anthem-ct";
    private static final String BUCKET = "pagw-dev-bucket";
    private static final String PAYLOAD_KEY = "parsed/PAGW-20251225-00001-TEST1234.json";

    @Mock
    private BusinessValidatorService validatorService;

    @Mock
    private S3Service s3Service;

    @Mock
    private RequestTrackerService trackerService;

    @Mock
    private EventTrackerService eventTrackerService;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private BusinessValidatorListener listener;

    private PagwMessage testMessage;
    private String messageBody;

    @BeforeEach
    void setUp() {
        testMessage = PagwMessage.builder()
                .messageId("msg-123")
                .pagwId(PAGW_ID)
                .schemaVersion("1.0")
                .stage("BUSINESS_VALIDATOR")
                .tenant(TENANT)
                .payloadBucket(BUCKET)
                .payloadKey(PAYLOAD_KEY)
                .createdAt(Instant.now())
                .build();
        
        messageBody = JsonUtils.toJson(testMessage);
    }

    @Test
    void testValidationSuccess_LogsVAL_START_and_VAL_OK() {
        // Arrange
        String claimData = "{\"pagwId\":\"" + PAGW_ID + "\",\"claimId\":\"CLM-001\"}";
        ValidationResult result = new ValidationResult();
        result.setPagwId(PAGW_ID);
        result.setValid(true);
        result.setWarnings(new ArrayList<>());
        
        when(s3Service.getObject(BUCKET, PAYLOAD_KEY)).thenReturn(claimData);
        when(validatorService.validate(claimData, testMessage)).thenReturn(result);
        
        // Act
        listener.handleMessage(messageBody, PAGW_ID);
        
        // Assert - VAL_START event logged
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventTrackerService).logStageStart(
                eq(PAGW_ID), 
                eq(TENANT), 
                eq("VALIDATOR"), 
                eventTypeCaptor.capture(), 
                isNull()
        );
        assertEquals(EventTracker.EVENT_VAL_START, eventTypeCaptor.getValue());
        
        // Assert - VAL_OK event logged
        ArgumentCaptor<String> completeEventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        
        verify(eventTrackerService).logStageComplete(
                eq(PAGW_ID),
                eq(TENANT),
                eq("VALIDATOR"),
                completeEventCaptor.capture(),
                durationCaptor.capture(),
                metadataCaptor.capture()
        );
        
        assertEquals(EventTracker.EVENT_VAL_OK, completeEventCaptor.getValue());
        assertTrue(durationCaptor.getValue() >= 0);
        assertTrue(metadataCaptor.getValue().contains("\"errorCount\":0"));
        assertTrue(metadataCaptor.getValue().contains("\"warningCount\":0"));
        
        // Verify no error events logged
        verify(eventTrackerService, never()).logStageError(
                anyString(), anyString(), anyString(), anyString(), 
                anyString(), anyString(), anyBoolean(), any()
        );
    }

    @Test
    void testValidationSuccess_WithWarnings() {
        // Arrange
        String claimData = "{\"pagwId\":\"" + PAGW_ID + "\"}";
        ValidationResult result = new ValidationResult();
        result.setPagwId(PAGW_ID);
        result.setValid(true);
        result.setWarnings(List.of(
                new com.anthem.pagw.validator.model.ValidationWarning("WARN_001", "Missing optional field", "optionalField")
        ));
        
        when(s3Service.getObject(BUCKET, PAYLOAD_KEY)).thenReturn(claimData);
        when(validatorService.validate(claimData, testMessage)).thenReturn(result);
        
        // Act
        listener.handleMessage(messageBody, PAGW_ID);
        
        // Assert - metadata includes warning count
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventTrackerService).logStageComplete(
                eq(PAGW_ID),
                eq(TENANT),
                eq("VALIDATOR"),
                eq(EventTracker.EVENT_VAL_OK),
                anyLong(),
                metadataCaptor.capture()
        );
        
        String metadata = metadataCaptor.getValue();
        assertTrue(metadata.contains("\"warningCount\":1"));
    }

    @Test
    void testValidationFailure_LogsVAL_FAIL() {
        // Arrange
        String claimData = "{\"pagwId\":\"" + PAGW_ID + "\"}";
        ValidationResult result = new ValidationResult();
        result.setPagwId(PAGW_ID);
        result.setValid(false);
        result.setErrors(List.of(
                new ValidationError("REQ_FIELD", "Missing required field: claimId", "claimId"),
                new ValidationError("REQ_FIELD", "Missing required field: patientReference", "patientReference")
        ));
        result.setWarnings(new ArrayList<>());
        result.setSummary("2 validation errors: Missing required field: claimId");
        
        when(s3Service.getObject(BUCKET, PAYLOAD_KEY)).thenReturn(claimData);
        when(validatorService.validate(claimData, testMessage)).thenReturn(result);
        
        // Act
        listener.handleMessage(messageBody, PAGW_ID);
        
        // Assert - VAL_START logged
        verify(eventTrackerService).logStageStart(
                eq(PAGW_ID), eq(TENANT), eq("VALIDATOR"), 
                eq(EventTracker.EVENT_VAL_START), isNull()
        );
        
        // Assert - VAL_FAIL logged (non-retryable)
        ArgumentCaptor<String> errorCodeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorMessageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> retryableCaptor = ArgumentCaptor.forClass(Boolean.class);
        
        verify(eventTrackerService).logStageError(
                eq(PAGW_ID),
                eq(TENANT),
                eq("VALIDATOR"),
                eq(EventTracker.EVENT_VAL_FAIL),
                errorCodeCaptor.capture(),
                errorMessageCaptor.capture(),
                retryableCaptor.capture(),
                isNull()
        );
        
        assertEquals("VALIDATION_FAILED", errorCodeCaptor.getValue());
        assertTrue(errorMessageCaptor.getValue().contains("validation errors"));
        assertFalse(retryableCaptor.getValue()); // Not retryable
        
        // Assert - VAL_OK NOT logged
        verify(eventTrackerService, never()).logStageComplete(
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()
        );
    }

    @Test
    void testValidationFailure_WithWarnings() {
        // Arrange
        String claimData = "{\"pagwId\":\"" + PAGW_ID + "\"}";
        ValidationResult result = new ValidationResult();
        result.setPagwId(PAGW_ID);
        result.setValid(false);
        result.setErrors(List.of(
                new ValidationError("INVALID_DATE", "Invalid date format", "serviceDate")
        ));
        result.setWarnings(List.of(
                new com.anthem.pagw.validator.model.ValidationWarning("WARN_001", "Optional field missing", "optionalField"),
                new com.anthem.pagw.validator.model.ValidationWarning("WARN_002", "Deprecated code used", "procedureCode")
        ));
        result.setSummary("1 validation errors: Invalid date format");
        
        when(s3Service.getObject(BUCKET, PAYLOAD_KEY)).thenReturn(claimData);
        when(validatorService.validate(claimData, testMessage)).thenReturn(result);
        
        // Act
        listener.handleMessage(messageBody, PAGW_ID);
        
        // Assert - VAL_FAIL event logged
        verify(eventTrackerService).logStageError(
                eq(PAGW_ID),
                eq(TENANT),
                eq("VALIDATOR"),
                eq(EventTracker.EVENT_VAL_FAIL),
                eq("VALIDATION_FAILED"),
                anyString(),
                eq(false),
                isNull()
        );
    }

    @Test
    void testValidationException_LogsVAL_FAIL_Retryable() {
        // Arrange
        when(s3Service.getObject(BUCKET, PAYLOAD_KEY)).thenThrow(new RuntimeException("S3 connection timeout"));
        
        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            listener.handleMessage(messageBody, PAGW_ID);
        });
        
        assertEquals("Failed to process message", exception.getMessage());
        
        // Assert - VAL_START logged
        verify(eventTrackerService).logStageStart(
                eq(PAGW_ID), eq(TENANT), eq("VALIDATOR"), 
                eq(EventTracker.EVENT_VAL_START), isNull()
        );
        
        // Assert - VAL_FAIL logged with retry enabled
        ArgumentCaptor<Boolean> retryableCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Instant> nextRetryCaptor = ArgumentCaptor.forClass(Instant.class);
        
        verify(eventTrackerService).logStageError(
                eq(PAGW_ID),
                eq(TENANT),
                eq("VALIDATOR"),
                eq(EventTracker.EVENT_VAL_FAIL),
                eq("VALIDATION_EXCEPTION"),
                contains("S3 connection timeout"),
                retryableCaptor.capture(),
                nextRetryCaptor.capture()
        );
        
        assertTrue(retryableCaptor.getValue()); // Retryable
        assertNotNull(nextRetryCaptor.getValue()); // Next retry time set
        
        // Verify tracker service updated with error
        verify(trackerService).updateStatus(
                eq(PAGW_ID),
                eq("VALIDATION_ERROR"),
                eq("business-validator"),
                eq("EXCEPTION"),
                contains("S3 connection timeout")
        );
    }

    @Test
    void testValidationEventSequence() {
        // Arrange
        String claimData = "{\"pagwId\":\"" + PAGW_ID + "\"}";
        ValidationResult result = new ValidationResult();
        result.setPagwId(PAGW_ID);
        result.setValid(true);
        result.setWarnings(new ArrayList<>());
        
        when(s3Service.getObject(BUCKET, PAYLOAD_KEY)).thenReturn(claimData);
        when(validatorService.validate(claimData, testMessage)).thenReturn(result);
        
        // Act
        listener.handleMessage(messageBody, PAGW_ID);
        
        // Assert - verify event sequence order
        var inOrder = inOrder(eventTrackerService, trackerService, validatorService, s3Service, outboxService);
        
        // 1. VAL_START event
        inOrder.verify(eventTrackerService).logStageStart(
                eq(PAGW_ID), eq(TENANT), eq("VALIDATOR"), 
                eq(EventTracker.EVENT_VAL_START), isNull()
        );
        
        // 2. Update tracker status
        inOrder.verify(trackerService).updateStatus(PAGW_ID, "VALIDATING", "business-validator");
        
        // 3. Fetch data from S3
        inOrder.verify(s3Service).getObject(BUCKET, PAYLOAD_KEY);
        
        // 4. Run validation
        inOrder.verify(validatorService).validate(claimData, testMessage);
        
        // 5. Store result in S3
        inOrder.verify(s3Service).putObject(eq(BUCKET), contains("validated"), anyString());
        
        // 6. Write to outbox
        inOrder.verify(outboxService).writeOutbox(anyString(), any(PagwMessage.class));
        
        // 7. Update tracker
        inOrder.verify(trackerService).updateStatus(PAGW_ID, "VALIDATED", "business-validator");
        
        // 8. VAL_OK event
        inOrder.verify(eventTrackerService).logStageComplete(
                eq(PAGW_ID), eq(TENANT), eq("VALIDATOR"), 
                eq(EventTracker.EVENT_VAL_OK), anyLong(), anyString()
        );
    }

    @Test
    void testDurationTracking() {
        // Arrange
        String claimData = "{\"pagwId\":\"" + PAGW_ID + "\"}";
        ValidationResult result = new ValidationResult();
        result.setPagwId(PAGW_ID);
        result.setValid(true);
        result.setWarnings(new ArrayList<>());
        
        when(s3Service.getObject(BUCKET, PAYLOAD_KEY)).thenReturn(claimData);
        when(validatorService.validate(claimData, testMessage)).thenReturn(result);
        
        // Act
        listener.handleMessage(messageBody, PAGW_ID);
        
        // Assert - duration is tracked and non-negative
        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        verify(eventTrackerService).logStageComplete(
                eq(PAGW_ID),
                eq(TENANT),
                eq("VALIDATOR"),
                eq(EventTracker.EVENT_VAL_OK),
                durationCaptor.capture(),
                anyString()
        );
        
        Long duration = durationCaptor.getValue();
        assertNotNull(duration);
        assertTrue(duration >= 0, "Duration should be non-negative");
    }
}
