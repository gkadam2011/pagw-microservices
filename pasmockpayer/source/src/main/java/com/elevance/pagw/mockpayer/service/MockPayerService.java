package com.elevance.pagw.mockpayer.service;

import com.elevance.pagw.mockpayer.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service that generates mock payer responses
 */
@Service
@Slf4j
public class MockPayerService {

    /**
     * Default approved response (A1)
     */
    public ResponseEntity<ApprovedResponse> handleApprovedScenario(
            Map<String, Object> request, String correlationId) {
        
        log.info("Generating APPROVED response");
        simulateProcessingDelay(200);
        
        ApprovedResponse response = ApprovedResponse.builder()
                .responseCode("A1")
                .responseStatus("APPROVED")
                .message("Prior authorization approved")
                .authorizationNumber("AUTH-" + generateId(12))
                .effectiveDate(Instant.now().toString().substring(0, 10))
                .expirationDate(Instant.now().plus(90, ChronoUnit.DAYS).toString().substring(0, 10))
                .approvedServices(List.of(
                        ApprovedService.builder()
                                .serviceCode("99213")
                                .approvedUnits(10)
                                .approvedVisits(5)
                                .unitType("VISIT")
                                .build()
                ))
                .providerNpi("1234567890")
                .payerName("Mock Payer Inc.")
                .payerId("MOCK001")
                .processedAt(Instant.now().toString())
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        return ResponseEntity.ok().headers(headers).body(response);
    }

    /**
     * Denied response (A2) - triggered by DENY-TEST
     */
    public ResponseEntity<DeniedResponse> handleDeniedScenario(
            Map<String, Object> request, String correlationId) {
        
        log.info("Generating DENIED response");
        simulateProcessingDelay(200);
        
        DeniedResponse response = DeniedResponse.builder()
                .responseCode("A2")
                .responseStatus("DENIED")
                .message("Prior authorization denied")
                .denialReasons(List.of(
                        DenialReason.builder()
                                .code("DN001")
                                .reason("SERVICE_NOT_MEDICALLY_NECESSARY")
                                .description("The requested service is not medically necessary based on clinical guidelines")
                                .build(),
                        DenialReason.builder()
                                .code("DN002")
                                .reason("ALTERNATIVE_AVAILABLE")
                                .description("A less costly alternative treatment is available")
                                .build()
                ))
                .appealInstructions(AppealInstructions.builder()
                        .deadline(Instant.now().plus(30, ChronoUnit.DAYS).toString().substring(0, 10))
                        .method("Written appeal with supporting clinical documentation")
                        .faxNumber("1-800-555-0199")
                        .address("Mock Payer Appeals, PO Box 12345, Anytown, ST 12345")
                        .build())
                .providerNpi("1234567890")
                .payerName("Mock Payer Inc.")
                .payerId("MOCK001")
                .processedAt(Instant.now().toString())
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        return ResponseEntity.ok().headers(headers).body(response);
    }

    /**
     * Pended response (A3) - triggered by PEND-TEST
     */
    public ResponseEntity<PendedResponse> handlePendedScenario(
            Map<String, Object> request, String correlationId) {
        
        log.info("Generating PENDED response");
        simulateProcessingDelay(500);
        
        PendedResponse response = PendedResponse.builder()
                .responseCode("A3")
                .responseStatus("PENDED")
                .message("Request received and pending clinical review")
                .pendReason("CLINICAL_REVIEW_REQUIRED")
                .pendDetails(PendDetails.builder()
                        .reviewType("MEDICAL_DIRECTOR")
                        .estimatedCompletionHours(24)
                        .estimatedCompletionTime(Instant.now().plus(24, ChronoUnit.HOURS).toString())
                        .build())
                .statusCheckInfo(StatusCheckInfo.builder()
                        .url("/api/status/" + UUID.randomUUID())
                        .pollingIntervalMinutes(30)
                        .build())
                .callbackInfo(CallbackInfo.builder()
                        .willCallback(true)
                        .callbackUrl("Configured in payer portal")
                        .build())
                .providerNpi("1234567890")
                .payerName("Mock Payer Inc.")
                .payerId("MOCK001")
                .receivedAt(Instant.now().toString())
                .processedAt(Instant.now().toString())
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        headers.add("X-Callback-Scheduled", "true");
        return ResponseEntity.status(HttpStatus.ACCEPTED).headers(headers).body(response);
    }

    /**
     * Additional info required response (A4) - triggered by MOREINFO-TEST
     */
    public ResponseEntity<MoreInfoResponse> handleMoreInfoScenario(
            Map<String, Object> request, String correlationId) {
        
        log.info("Generating ADDITIONAL_INFO_REQUIRED response");
        simulateProcessingDelay(300);
        
        MoreInfoResponse response = MoreInfoResponse.builder()
                .responseCode("A4")
                .responseStatus("ADDITIONAL_INFO_REQUIRED")
                .message("Additional clinical documentation is required to process this request")
                .requiredDocuments(List.of(
                        RequiredDocument.builder()
                                .documentType("CLINICAL_NOTES")
                                .description("Most recent clinical notes from treating physician (last 30 days)")
                                .required(true)
                                .acceptedFormats(List.of("PDF", "TIFF", "CDA"))
                                .build(),
                        RequiredDocument.builder()
                                .documentType("LAB_RESULTS")
                                .description("Relevant laboratory results supporting medical necessity")
                                .required(true)
                                .acceptedFormats(List.of("PDF", "HL7"))
                                .build(),
                        RequiredDocument.builder()
                                .documentType("IMAGING_REPORT")
                                .description("Previous imaging studies if available")
                                .required(false)
                                .acceptedFormats(List.of("PDF", "DICOM"))
                                .build()
                ))
                .submissionInfo(SubmissionInfo.builder()
                        .deadline(Instant.now().plus(14, ChronoUnit.DAYS).toString().substring(0, 10))
                        .submissionUrl("/api/attachments/submit")
                        .referenceNumber("REF-" + generateId(10))
                        .maxFileSizeMB(25)
                        .build())
                .contactInfo(ContactInfo.builder()
                        .phone("1-800-555-0123")
                        .email("pa-support@mockpayer.com")
                        .hours("Monday-Friday 8AM-6PM EST")
                        .build())
                .providerNpi("1234567890")
                .payerName("Mock Payer Inc.")
                .payerId("MOCK001")
                .processedAt(Instant.now().toString())
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        return ResponseEntity.ok().headers(headers).body(response);
    }

    /**
     * 500 Error response - triggered by ERROR-TEST
     */
    public ResponseEntity<ErrorResponse> handleErrorScenario(String correlationId) {
        log.info("Generating ERROR response");
        
        ErrorResponse response = ErrorResponse.builder()
                .error("INTERNAL_SERVER_ERROR")
                .errorCode("E500")
                .message("An unexpected error occurred while processing your request")
                .details("The payer system encountered an internal error. Please retry your request.")
                .errorId("ERR-" + UUID.randomUUID())
                .timestamp(Instant.now().toString())
                .retryable(true)
                .suggestedRetryAfterSeconds(30)
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        headers.add("X-Error-Id", response.getErrorId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).headers(headers).body(response);
    }

    /**
     * Timeout simulation - triggered by TIMEOUT-TEST
     */
    public ResponseEntity<ApprovedResponse> handleTimeoutScenario(String correlationId) {
        log.info("Simulating TIMEOUT (35 second delay)");
        
        try {
            Thread.sleep(35000); // 35 second delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ApprovedResponse response = ApprovedResponse.builder()
                .responseCode("A1")
                .responseStatus("APPROVED")
                .message("This response was intentionally delayed to simulate timeout")
                .authorizationNumber("AUTH-" + generateId(12))
                .processedAt(Instant.now().toString())
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        return ResponseEntity.ok().headers(headers).body(response);
    }

    /**
     * Rate limit response - triggered by RATELIMIT-TEST
     */
    public ResponseEntity<ErrorResponse> handleRateLimitScenario(String correlationId) {
        log.info("Generating RATE_LIMIT response");
        
        ErrorResponse response = ErrorResponse.builder()
                .error("RATE_LIMIT_EXCEEDED")
                .errorCode("E429")
                .message("Too many requests. You have exceeded the rate limit.")
                .timestamp(Instant.now().toString())
                .retryable(true)
                .suggestedRetryAfterSeconds(60)
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        headers.add("Retry-After", "60");
        headers.add("X-RateLimit-Limit", "100");
        headers.add("X-RateLimit-Remaining", "0");
        headers.add("X-RateLimit-Reset", Instant.now().plus(1, ChronoUnit.MINUTES).toString());
        
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(response);
    }

    /**
     * Unauthorized response - triggered by UNAUTH-TEST
     */
    public ResponseEntity<ErrorResponse> handleUnauthorizedScenario() {
        log.info("Generating UNAUTHORIZED response");
        
        ErrorResponse response = ErrorResponse.builder()
                .error("UNAUTHORIZED")
                .errorCode("E401")
                .message("Authentication failed or token has expired")
                .details("Please obtain a new access token and retry the request")
                .timestamp(Instant.now().toString())
                .build();
        
        HttpHeaders headers = new HttpHeaders();
        headers.add("WWW-Authenticate", "Bearer realm=\"payer-api\", error=\"invalid_token\"");
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).headers(headers).body(response);
    }

    /**
     * Bad request response - triggered by BADREQUEST-TEST
     */
    public ResponseEntity<ValidationErrorResponse> handleBadRequestScenario(String correlationId) {
        log.info("Generating BAD_REQUEST response");
        
        ValidationErrorResponse response = ValidationErrorResponse.builder()
                .error("BAD_REQUEST")
                .errorCode("E400")
                .message("The request contains invalid or missing data")
                .validationErrors(List.of(
                        ValidationError.builder()
                                .field("subscriberId")
                                .code("REQUIRED")
                                .message("Subscriber ID is required")
                                .build(),
                        ValidationError.builder()
                                .field("serviceDate")
                                .code("INVALID_FORMAT")
                                .message("Service date must be in YYYY-MM-DD format")
                                .build(),
                        ValidationError.builder()
                                .field("providerNpi")
                                .code("INVALID_LENGTH")
                                .message("Provider NPI must be exactly 10 digits")
                                .build()
                ))
                .timestamp(Instant.now().toString())
                .build();
        
        HttpHeaders headers = createHeaders(correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(response);
    }

    // Helper methods
    
    private HttpHeaders createHeaders(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        if (correlationId != null) {
            headers.add("X-Correlation-Id", correlationId);
        }
        headers.add("X-Payer-Transaction-Id", "PAYER-" + UUID.randomUUID());
        return headers;
    }
    
    private void simulateProcessingDelay(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private String generateId(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int index = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }
}
