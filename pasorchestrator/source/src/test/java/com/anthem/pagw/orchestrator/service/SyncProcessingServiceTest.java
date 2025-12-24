package com.anthem.pagw.orchestrator.service;

import com.anthem.pagw.core.PagwProperties;
import com.anthem.pagw.core.service.RequestTrackerService;
import com.anthem.pagw.core.service.S3Service;
import com.anthem.pagw.orchestrator.model.SyncProcessingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SyncProcessingService - focused on sync processing outcomes.
 */
@ExtendWith(MockitoExtension.class)
class SyncProcessingServiceTest {

    @Mock(lenient = true)
    private RestTemplate restTemplate;

    @Mock(lenient = true)
    private S3Service s3Service;

    @Mock(lenient = true)
    private RequestTrackerService requestTrackerService;

    @Mock(lenient = true)
    private PagwProperties properties;

    private ObjectMapper objectMapper;
    private SyncProcessingService syncProcessingService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        PagwProperties.Aws aws = new PagwProperties.Aws();
        PagwProperties.S3 s3 = new PagwProperties.S3();
        s3.setRequestBucket("test-bucket");
        aws.setS3(s3);
        when(properties.getAws()).thenReturn(aws);
        
        syncProcessingService = new SyncProcessingService(
                restTemplate,
                objectMapper,
                s3Service,
                requestTrackerService,
                properties
        );
        
        // Configure via reflection
        ReflectionTestUtils.setField(syncProcessingService, "requestParserUrl", "http://parser:443");
        ReflectionTestUtils.setField(syncProcessingService, "businessValidatorUrl", "http://validator:443");
        ReflectionTestUtils.setField(syncProcessingService, "syncEnabled", true);
        ReflectionTestUtils.setField(syncProcessingService, "syncTimeoutSeconds", 1); // Short timeout for tests
    }

    @Test
    void shouldReturnPendedWhenParserSucceedsButValidatorIndicatesPended() {
        // Given: Parser succeeds, validator returns pended disposition
        String pagwId = "TEST-001";
        String fhirBundle = "{\"resourceType\":\"Bundle\"}";

        ResponseEntity<String> parserResponse = new ResponseEntity<>(
                "{\"status\":\"success\",\"parsedData\":{}}",
                HttpStatus.OK
        );
        
        ResponseEntity<String> validatorResponse = new ResponseEntity<>(
                "{\"valid\":true,\"disposition\":\"pended\",\"reason\":\"Additional review required\"}",
                HttpStatus.OK
        );
        
        when(restTemplate.exchange(contains("parser"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(parserResponse);
        when(restTemplate.exchange(contains("validator"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(validatorResponse);

        // When: Process synchronously
        SyncProcessingResult result = syncProcessingService.processSync(pagwId, fhirBundle, "ANTHEM", null);

        // Then: Should return pended result
        assertNotNull(result);
        assertEquals(SyncProcessingResult.Disposition.PENDED, result.getDisposition());
    }

    @Test
    void shouldHandleParserNetworkFailure() {
        // Given: Parser service is unavailable
        String pagwId = "TEST-002";
        String fhirBundle = "{\"resourceType\":\"Bundle\"}";

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        // When: Process synchronously
        SyncProcessingResult result = syncProcessingService.processSync(pagwId, fhirBundle, "ANTHEM", null);

        // Then: Should return pended result (not error, queued for async)
        assertNotNull(result);
        assertEquals(SyncProcessingResult.Disposition.PENDED, result.getDisposition());
        assertFalse(result.isCompleted());
    }

    @Test
    void shouldHandleParserErrorResponse() {
        // Given: Parser returns error response
        String pagwId = "TEST-003";
        String fhirBundle = "invalid-json";

        ResponseEntity<String> parserResponse = new ResponseEntity<>(
                "{\"status\":\"error\",\"message\":\"Invalid bundle\"}",
                HttpStatus.BAD_REQUEST
        );
        
        when(restTemplate.exchange(contains("parser"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(parserResponse);

        // When: Process synchronously
        SyncProcessingResult result = syncProcessingService.processSync(pagwId, fhirBundle, "ANTHEM", null);

        // Then: Should return result indicating parser failure
        assertNotNull(result);
        assertFalse(result.isCompleted() && result.isValid());
    }

    @Test
    void shouldReturnEnabledStatus() {
        // When: Check if sync processing is enabled
        boolean enabled = syncProcessingService.isSyncEnabled();

        // Then: Should return true (set in setUp)
        assertTrue(enabled);
    }

    @Test
    void shouldHandleValidatorReturningValidationErrors() {
        // Given: Parser succeeds, validator returns validation errors
        String pagwId = "TEST-004";
        String fhirBundle = "{\"resourceType\":\"Bundle\"}";

        ResponseEntity<String> parserResponse = new ResponseEntity<>(
                "{\"status\":\"success\",\"parsedData\":{}}",
                HttpStatus.OK
        );
        
        ResponseEntity<String> validatorResponse = new ResponseEntity<>(
                "{\"valid\":false,\"errors\":[{\"code\":\"VAL-001\",\"message\":\"Missing required field\"}]}",
                HttpStatus.OK
        );
        
        when(restTemplate.exchange(contains("parser"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(parserResponse);
        when(restTemplate.exchange(contains("validator"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(validatorResponse);

        // When: Process synchronously
        SyncProcessingResult result = syncProcessingService.processSync(pagwId, fhirBundle, "ANTHEM", null);

        // Then: Should return completed but invalid result
        assertNotNull(result);
        assertTrue(result.isCompleted());
        assertFalse(result.isValid());
    }
}
