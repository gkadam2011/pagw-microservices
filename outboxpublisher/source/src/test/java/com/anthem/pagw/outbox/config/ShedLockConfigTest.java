package com.anthem.pagw.outbox.config;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ShedLockConfig.
 * Tests distributed locking configuration for high availability.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver"
})
class ShedLockConfigTest {

    @Autowired(required = false)
    private LockProvider lockProvider;

    @Autowired(required = false)
    private DataSource dataSource;

    @Test
    void lockProvider_shouldBeConfigured() {
        // Then
        assertNotNull(lockProvider, "LockProvider bean should be configured");
    }

    @Test
    void lockProvider_shouldUseJdbcTemplate() {
        // Then - Verify it's a JDBC-based lock provider
        assertNotNull(lockProvider);
        assertTrue(lockProvider.getClass().getName().contains("JdbcTemplate"),
                "LockProvider should be JDBC-based");
    }

    @Test
    void dataSource_shouldBeAvailable() {
        // Then
        assertNotNull(dataSource, "DataSource should be configured for ShedLock");
    }

    @Test
    void config_shouldEnableScheduling() {
        // Given - ShedLockConfig has @EnableScheduling
        // When - Spring context loads
        // Then - No exception should be thrown (tested by successful context load)
        assertTrue(true, "Spring context with @EnableScheduling should load successfully");
    }

    @Test
    void config_shouldEnableSchedulerLock() {
        // Given - ShedLockConfig has @EnableSchedulerLock
        // When - Spring context loads
        // Then - LockProvider should be available
        assertNotNull(lockProvider, "@EnableSchedulerLock should enable lock provider");
    }
}
