package com.elevance.pagw.mockpayer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mock Payer Service for PAGW Testing
 * 
 * This service simulates payer API responses for testing the
 * Prior Authorization Gateway without connecting to real payers.
 * 
 * Response Triggers (include in request body):
 * - Default: Approved (A1)
 * - DENY-TEST: Denied (A2)
 * - PEND-TEST: Pended for review (A3)
 * - MOREINFO-TEST: Additional info required (A4)
 * - ERROR-TEST: 500 Internal Server Error
 * - TIMEOUT-TEST: 35 second delay
 * - RATELIMIT-TEST: 429 Rate Limited
 */
@SpringBootApplication
public class MockPayerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPayerApplication.class, args);
    }
}
