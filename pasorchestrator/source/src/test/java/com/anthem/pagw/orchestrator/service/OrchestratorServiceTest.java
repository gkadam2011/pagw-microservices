package com.anthem.pagw.orchestrator.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.model.RequestTracker;
import com.anthem.pagw.core.service.*;
import com.anthem.pagw.orchestrator.model.PasRequest;
import com.anthem.pagw.orchestrator.model.PasResponse;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrchestratorService - focused on business logic and outcomes.
 */
@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock(lenient = true)
    private S3Service s3Service;

    @Mock(lenient = true)
    private RequestTrackerService requestTrackerService;

    @Mock(lenient = true)
    private EventTrackerService eventTrackerService;

    @Mock(lenient = true)
    private OutboxService outboxService;

    @Mock(lenient = true)
    private PhiEncryptionService phiEncryptionService;

    @Mock(lenient = true)
    private AuditService auditService;

    @Mock(lenient = true)
    private IdempotencyService idempotencyService;

    @Mock(lenient = true)
    private SyncProcessingService syncProcessingService;

    @Mock(lenient = true)
    private PagwProperties properties;

    private OrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        // Setup properties with nested objects
        PagwProperties.Aws aws = new PagwProperties.Aws();
        PagwProperties.S3 s3 = new PagwProperties.S3();
        s3.setRequestBucket("test-bucket");
        aws.setS3(s3);
        
        PagwProperties.Encryption encryption = new PagwProperties.Encryption();
        encryption.setEncryptPhiFields(false);
        
        PagwProperties.Queues queues = new PagwProperties.Queues();
        queues.setRequestParser("test-queue");
        
        when(properties.getAws()).thenReturn(aws);
        when(properties.getEncryption()).thenReturn(encryption);
        when(properties.getQueues()).thenReturn(queues);
        
        // Default behaviors
        when(idempotencyService.checkAndSet(anyString())).thenReturn(true);
        when(requestTrackerService.tryMarkAsyncQueued(anyString())).thenReturn(true);
        when(requestTrackerService.create(any(RequestTracker.class))).thenAnswer(i -> i.getArgument(0));
        
        orchestratorService = new OrchestratorService(
                s3Service,
                requestTrackerService,
                eventTrackerService,
                outboxService,
                phiEncryptionService,
                auditService,
                idempotencyService,
                syncProcessingService,
                properties
        );
    }

    @Test
    void shouldProcessSyncRequestSuccessfully() {
        // Given: Valid request with sync processing enabled
        String fhirBundle = "{\"resourceType\":\"Bundle\",\"type\":\"collection\"}";
        PasRequest request = PasRequest.builder()
                .fhirBundle(fhirBundle)
                .requestType("SUBMIT")
                .tenant("ANTHEM")
                .syncProcessing(true)
                .build();

        when(syncProcessingService.isSyncEnabled()).thenReturn(true);
        
        SyncProcessingResult syncResult = SyncProcessingResult.builder()
                .completed(true)
                .valid(true)
                .disposition(SyncProcessingResult.Disposition.APPROVED)
                .processingTimeMs(100)
                .claimResponseBundle("{\"resourceType\":\"ClaimResponse\"}")
                .build();
        
        when(syncProcessingService.processSync(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(syncResult);

        // When: Process the request
        PasResponse response = orchestratorService.processRequest(request);

        // Then: Should return approved response
        assertNotNull(response);
        assertEquals("approved", response.getStatus());
        assertNotNull(response.getPagwId());
        
        // Verify critical operations occurred
        verify(requestTrackerService).create(any(RequestTracker.class));
        verify(syncProcessingService).processSync(anyString(), eq(fhirBundle), eq("ANTHEM"), isNull());
    }

    @Test
    void shouldHandleValidationErrors() {
        // Given: Request that fails validation
        PasRequest request = PasRequest.builder()
                .fhirBundle("{\"resourceType\":\"Bundle\"}")
                .requestType("SUBMIT")
                .syncProcessing(true)
                .build();

        when(syncProcessingService.isSyncEnabled()).thenReturn(true);

        SyncProcessingResult.ValidationError error = SyncProcessingResult.ValidationError.builder()
                .code("VAL-001")
                .severity("error")
                .location("Patient.name")
                .message("Patient name is required")
                .issueType(SyncProcessingResult.ValidationError.ISSUE_REQUIRED)
                .build();

        SyncProcessingResult syncResult = SyncProcessingResult.builder()
                .completed(true)
                .valid(false)
                .validationErrors(List.of(error))
                .processingTimeMs(50)
                .build();

        when(syncProcessingService.processSync(anyString(), anyString(), isNull(), isNull()))
                .thenReturn(syncResult);

        // When: Process the request
        PasResponse response = orchestratorService.processRequest(request);

        // Then: Should return error response with validation details
        assertNotNull(response);
        assertEquals("error", response.getStatus());
        assertNotNull(response.getValidationErrors());
        assertFalse(response.getValidationErrors().isEmpty());
    }

    @Test
    void shouldDetectDuplicateRequests() {
        // Given: Duplicate request (idempotency check fails)
        PasRequest request = PasRequest.builder()
                .fhirBundle("{}")
                .idempotencyKey("duplicate-key")
                .build();

        when(idempotencyService.checkAndSet("duplicate-key")).thenReturn(false);

        // When: Process the duplicate request
        PasResponse response = orchestratorService.processRequest(request);

        // Then: Should return duplicate response without processing
        assertNotNull(response);
        assertEquals("duplicate", response.getStatus());
        
        // Verify no actual processing occurred
        verifyNoInteractions(s3Service);
        verifyNoInteractions(syncProcessingService);
    }

    @Test
    void shouldHandleSyncTimeout() {
        // Given: Request that times out during sync processing
        PasRequest request = PasRequest.builder()
                .fhirBundle("{\"resourceType\":\"Bundle\"}")
                .requestType("SUBMIT")
                .syncProcessing(true)
                .build();

        when(syncProcessingService.isSyncEnabled()).thenReturn(true);

        SyncProcessingResult syncResult = SyncProcessingResult.builder()
                .completed(false)
                .disposition(SyncProcessingResult.Disposition.PENDED)
                .lastStage("VALIDATOR")
                .processingTimeMs(13000)
                .build();

        when(syncProcessingService.processSync(anyString(), anyString(), isNull(), isNull()))
                .thenReturn(syncResult);

        // When: Process the request
        PasResponse response = orchestratorService.processRequest(request);

        // Then: Should return pended response and queue for async
        assertNotNull(response);
        assertEquals("pended", response.getStatus());
        assertNotNull(response.getPagwId());
        
        // Should have attempted to queue for async processing
        verify(requestTrackerService).tryMarkAsyncQueued(anyString());
    }

    @Test
    void shouldStoreRequestInS3() {
        // Given: Any valid request
        String fhirBundle = "{\"resourceType\":\"Bundle\"}";
        PasRequest request = PasRequest.builder()
                .fhirBundle(fhirBundle)
                .syncProcessing(false)
                .build();

        // When: Process the request
        orchestratorService.processRequest(request);

        // Then: Should store the bundle in S3
        verify(s3Service).putObject(eq("test-bucket"), anyString(), eq(fhirBundle));
    }

    @Test
    void shouldCreateRequestTracker() {
        // Given: Any valid request
        PasRequest request = PasRequest.builder()
                .fhirBundle("{}")
                .tenant("TEST_TENANT")
                .requestType("SUBMIT")
                .syncProcessing(false)
                .build();

        // When: Process the request
        orchestratorService.processRequest(request);

        // Then: Should create a request tracker entry
        verify(requestTrackerService).create(argThat(tracker -> 
            tracker.getTenant().equals("TEST_TENANT") &&
            tracker.getRequestType().equals("SUBMIT") &&
            tracker.getStatus().equals(RequestTracker.STATUS_RECEIVED)
        ));
    }
}
