package com.anthem.pagw.auth.service;

import com.anthem.pagw.auth.model.ProviderContext;
import com.anthem.pagw.auth.model.ProviderRegistration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Service for managing provider registrations.
 * Uses Aurora PostgreSQL for lookups during authorization.
 */
public class ProviderRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistryService.class);
    
    private final HikariDataSource dataSource;
    private final String environment;
    
    // In-memory cache for performance
    private final Map<String, ProviderRegistration> cache = new HashMap<>();
    private final Map<String, Instant> cacheExpiry = new HashMap<>();
    private static final long CACHE_TTL_SECONDS = 300; // 5 minutes

    public ProviderRegistryService() {
        this.environment = System.getenv().getOrDefault("ENVIRONMENT", "dev");
        this.dataSource = initDataSource();
    }

    private HikariDataSource initDataSource() {
        try {
            // Aligned with Helm template: PAGW_AURORA_WRITER_ENDPOINT, PAGW_AURORA_PORT, PAGW_AURORA_DATABASE
            String rdsProxyEndpoint = System.getenv("PAGW_AURORA_WRITER_ENDPOINT");
            String dbName = System.getenv().getOrDefault("PAGW_AURORA_DATABASE", "pagw");
            String dbPort = System.getenv().getOrDefault("PAGW_AURORA_PORT", "5432");
            String dbSecretArn = System.getenv("PAGW_AURORA_SECRET_ARN");
            
            if (rdsProxyEndpoint == null || dbSecretArn == null) {
                log.warn("Database not configured - PAGW_AURORA_WRITER_ENDPOINT or PAGW_AURORA_SECRET_ARN missing");
                return null;
            }
            
            // Get credentials from Secrets Manager
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-2")))
                    .build();
            
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretArn)
                    .build();
            
            String secretJson = secretsClient.getSecretValue(request).secretString();
            @SuppressWarnings("unchecked")
            Map<String, String> secret = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(secretJson, Map.class);
            
            String username = secret.get("username");
            String password = secret.get("password");
            
            // Configure HikariCP with env vars from Helm template
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(String.format("jdbc:postgresql://%s:%s/%s", rdsProxyEndpoint, dbPort, dbName));
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(Integer.parseInt(System.getenv().getOrDefault("PAGW_AURORA_MAX_POOL_SIZE", "5"))); // Lambda: keep pool small
            config.setMinimumIdle(Integer.parseInt(System.getenv().getOrDefault("PAGW_AURORA_MIN_IDLE", "1")));
            config.setIdleTimeout(30000);
            config.setConnectionTimeout(5000);
            config.setMaxLifetime(60000);
            config.addDataSourceProperty("ssl", "true");
            config.addDataSourceProperty("sslmode", "require");
            
            return new HikariDataSource(config);
            
        } catch (Exception e) {
            log.error("Failed to initialize database connection", e);
            return null;
        }
    }

    /**
     * Get provider details and enrich the context.
     * 
     * @param context Initial provider context from token
     * @return Enriched context with registration details, or null if not found
     */
    public ProviderContext getProviderDetails(ProviderContext context) {
        if (context == null || context.getClientId() == null) {
            return null;
        }
        
        String clientId = context.getClientId();
        
        // Check cache first
        ProviderRegistration registration = getCachedRegistration(clientId);
        
        if (registration == null) {
            // Lookup in PostgreSQL
            registration = lookupProvider(clientId);
            
            if (registration == null) {
                // In dev, create a default registration
                if ("dev".equals(environment)) {
                    return createDevContext(context);
                }
                return null;
            }
            
            // Cache for future requests
            cacheRegistration(clientId, registration);
        }
        
        // Update last auth timestamp asynchronously
        updateLastAuth(clientId);
        
        // Enrich context with registration details
        return ProviderContext.builder()
                .clientId(clientId)
                .tenant(registration.getTenant())
                .providerName(registration.getProviderName())
                .providerType(registration.getProviderType())
                .npi(registration.getNpi())
                .taxId(registration.getTaxId())
                .active(registration.isActive())
                .permissions(registration.getPermissions() != null ? 
                        registration.getPermissions() : Set.of())
                .rateLimit(registration.getRateLimit() > 0 ? 
                        registration.getRateLimit() : 100)
                .issuer(context.getIssuer())
                .subject(context.getSubject())
                .tokenExpiry(context.getTokenExpiry())
                .environment(registration.getEnvironment())
                .claims(context.getClaims())
                .build();
    }

    private ProviderRegistration lookupProvider(String clientId) {
        if (dataSource == null) {
            log.warn("Database not available, cannot lookup provider");
            return null;
        }
        
        String sql = """
            SELECT client_id, tenant, provider_name, provider_type, npi, tax_id,
                   active, permissions, rate_limit, issuer_url, jwks_url, 
                   environment, callback_url, contact_email
            FROM provider_registry
            WHERE client_id = ?
            """;
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, clientId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSet(rs);
                }
            }
            
            log.warn("Provider not found: clientId={}", clientId);
            return null;
            
        } catch (SQLException e) {
            log.error("Failed to lookup provider: clientId={}", clientId, e);
            return null;
        }
    }

    private ProviderRegistration mapResultSet(ResultSet rs) throws SQLException {
        // Parse permissions from PostgreSQL array
        Set<String> permissions = new HashSet<>();
        Array permArray = rs.getArray("permissions");
        if (permArray != null) {
            String[] perms = (String[]) permArray.getArray();
            permissions.addAll(Arrays.asList(perms));
        }
        
        return ProviderRegistration.builder()
                .clientId(rs.getString("client_id"))
                .tenant(rs.getString("tenant"))
                .providerName(rs.getString("provider_name"))
                .providerType(rs.getString("provider_type"))
                .npi(rs.getString("npi"))
                .taxId(rs.getString("tax_id"))
                .active(rs.getBoolean("active"))
                .permissions(permissions)
                .rateLimit(rs.getInt("rate_limit"))
                .issuerUrl(rs.getString("issuer_url"))
                .jwksUrl(rs.getString("jwks_url"))
                .environment(rs.getString("environment"))
                .callbackUrl(rs.getString("callback_url"))
                .contactEmail(rs.getString("contact_email"))
                .build();
    }

    private ProviderRegistration getCachedRegistration(String clientId) {
        Instant expiry = cacheExpiry.get(clientId);
        if (expiry != null && Instant.now().isBefore(expiry)) {
            return cache.get(clientId);
        }
        return null;
    }

    private void cacheRegistration(String clientId, ProviderRegistration registration) {
        cache.put(clientId, registration);
        cacheExpiry.put(clientId, Instant.now().plusSeconds(CACHE_TTL_SECONDS));
    }

    private void updateLastAuth(String clientId) {
        if (dataSource == null) return;
        
        // Run async to not block authorization
        new Thread(() -> {
            String sql = "UPDATE provider_registry SET last_auth_at = NOW() WHERE client_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, clientId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                log.debug("Failed to update lastAuthAt: clientId={}", clientId);
            }
        }).start();
    }

    private ProviderContext createDevContext(ProviderContext context) {
        log.warn("DEV MODE: Creating default context for unregistered client: {}", context.getClientId());
        
        return ProviderContext.builder()
                .clientId(context.getClientId())
                .tenant(context.getTenant() != null ? context.getTenant() : "dev")
                .providerName(context.getProviderName() != null ? context.getProviderName() : "Dev Provider")
                .providerType("PROVIDER")
                .active(true)
                .permissions(Set.of("/*")) // Full access in dev
                .rateLimit(1000)
                .issuer(context.getIssuer())
                .subject(context.getSubject())
                .tokenExpiry(context.getTokenExpiry())
                .environment("dev")
                .claims(context.getClaims())
                .build();
    }

    /**
     * Register a new provider (for admin use).
     */
    public void registerProvider(ProviderRegistration registration) {
        if (dataSource == null) {
            throw new RuntimeException("Database not available");
        }
        
        String sql = """
            INSERT INTO provider_registry (
                client_id, tenant, provider_name, provider_type, npi, tax_id,
                active, permissions, rate_limit, issuer_url, jwks_url,
                environment, callback_url, contact_email, registered_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (client_id) DO UPDATE SET
                tenant = EXCLUDED.tenant,
                provider_name = EXCLUDED.provider_name,
                provider_type = EXCLUDED.provider_type,
                npi = EXCLUDED.npi,
                tax_id = EXCLUDED.tax_id,
                active = EXCLUDED.active,
                permissions = EXCLUDED.permissions,
                rate_limit = EXCLUDED.rate_limit,
                issuer_url = EXCLUDED.issuer_url,
                jwks_url = EXCLUDED.jwks_url,
                environment = EXCLUDED.environment,
                callback_url = EXCLUDED.callback_url,
                contact_email = EXCLUDED.contact_email,
                updated_at = NOW()
            """;
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, registration.getClientId());
            stmt.setString(2, registration.getTenant());
            stmt.setString(3, registration.getProviderName());
            stmt.setString(4, registration.getProviderType());
            stmt.setString(5, registration.getNpi());
            stmt.setString(6, registration.getTaxId());
            stmt.setBoolean(7, registration.isActive());
            
            // Convert Set to PostgreSQL array
            if (registration.getPermissions() != null && !registration.getPermissions().isEmpty()) {
                Array permArray = conn.createArrayOf("TEXT", registration.getPermissions().toArray());
                stmt.setArray(8, permArray);
            } else {
                stmt.setNull(8, Types.ARRAY);
            }
            
            stmt.setInt(9, registration.getRateLimit());
            stmt.setString(10, registration.getIssuerUrl());
            stmt.setString(11, registration.getJwksUrl());
            stmt.setString(12, registration.getEnvironment());
            stmt.setString(13, registration.getCallbackUrl());
            stmt.setString(14, registration.getContactEmail());
            
            stmt.executeUpdate();
            
            log.info("Registered provider: clientId={}, tenant={}", 
                    registration.getClientId(), registration.getTenant());
            
            // Invalidate cache
            cache.remove(registration.getClientId());
            cacheExpiry.remove(registration.getClientId());
            
        } catch (SQLException e) {
            log.error("Failed to register provider", e);
            throw new RuntimeException("Failed to register provider", e);
        }
    }

    /**
     * Close the data source (for cleanup).
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
