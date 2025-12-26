package com.anthem.pagw.orchestrator.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway configuration that supports resetting migration history.
 * 
 * When resetDbMigration=true:
 * - Cleans the flyway_schema_history table (repair + clean)
 * - Re-runs all migrations from scratch
 * 
 * Usage:
 * - Environment variable: RESET_DB_MIGRATION=true
 * - System property: -Dreset.db.migration=true
 * - application.yml: pagw.reset-db-migration: true
 * 
 * WARNING: Only use in development/testing environments!
 */
@Configuration
public class FlywayResetConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayResetConfig.class);

    @Value("${pagw.reset-db-migration:false}")
    private boolean resetDbMigration;

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            if (resetDbMigration) {
                log.warn("========================================");
                log.warn("FLYWAY RESET MODE ENABLED!");
                log.warn("Cleaning migration history and re-running all migrations.");
                log.warn("========================================");
                
                // Repair first to fix any checksum mismatches
                flyway.repair();
                log.info("Flyway repair completed");
                
                // Clean drops all objects in the configured schemas
                // This includes the flyway_schema_history table
                flyway.clean();
                log.info("Flyway clean completed - all schema objects dropped");
            } else {
                // Always run repair in dev to handle checksum mismatches from migration file changes
                log.info("Running Flyway repair to sync checksums...");
                flyway.repair();
                log.info("Flyway repair completed");
            }
            
            // Run migrations (baseline will be applied if needed)
            flyway.migrate();
            log.info("Flyway migration completed");
        };
    }
}
