package com.anthem.pagw.outbox.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * ShedLock Configuration for distributed scheduling.
 * 
 * Ensures that scheduled tasks (like outbox publishing) only run on ONE pod
 * at a time, even when multiple replicas are deployed for high availability.
 * 
 * This prevents:
 * - Duplicate message publishing
 * - Race conditions between pods
 * - Wasted resources from redundant processing
 * 
 * Requires the shedlock table to exist in the database:
 * 
 * CREATE TABLE IF NOT EXISTS shedlock (
 *     name VARCHAR(64) NOT NULL PRIMARY KEY,
 *     lock_until TIMESTAMP NOT NULL,
 *     locked_at TIMESTAMP NOT NULL,
 *     locked_by VARCHAR(255) NOT NULL
 * );
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
public class ShedLockConfig {

    /**
     * Configure JDBC-based lock provider.
     * Uses the same database as the outbox table for consistency.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()
                        .build()
        );
    }
}
