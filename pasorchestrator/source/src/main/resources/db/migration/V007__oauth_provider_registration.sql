-- ============================================================================
-- V003: OAuth Provider Registration Support
-- ============================================================================
-- Purpose: Add OAuth/TotalView provider registration table separate from
--          provider-to-payer routing configuration.
--
-- Background: provider_registry (V001) tracks provider NPI → payer routing.
--             This new table tracks OAuth clientId → provider identity/entitlements
--             needed for API Gateway Lambda authorizer.
--
-- See: docs/provider-auth-registration.md for design details
-- ============================================================================

-- ============================================================================
-- 1. OAUTH_PROVIDER_REGISTRY - OAuth client registration and entitlements
-- ============================================================================
CREATE TABLE IF NOT EXISTS pagw.oauth_provider_registry (
    -- OAuth Identity (from TotalView introspection)
    client_id               VARCHAR(255) PRIMARY KEY,
    
    -- Tenant/Brand
    tenant                  VARCHAR(50) NOT NULL,           -- elevance, carelon
    
    -- Organization Identity
    entity_name             VARCHAR(255) NOT NULL,
    entity_type             VARCHAR(50) NOT NULL,           -- PROVIDER, FACILITY, CLEARINGHOUSE, EHR, VENDOR
    
    -- Provider Identifiers
    npis                    TEXT[],                         -- Multiple NPIs supported
    tins                    TEXT[],                         -- Multiple TINs supported
    
    -- Authorization
    status                  VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, SUSPENDED, REVOKED
    allowed_apis            TEXT[] NOT NULL DEFAULT '{}',   -- PAS_SUBMIT, PAS_INQUIRE, CDEX_SUBMIT_ATTACHMENT, PAS_SUBSCRIBE
    scopes                  TEXT[],                         -- OAuth scopes (if applicable)
    
    -- Rate Limiting
    rate_limit_per_minute   INT DEFAULT 100,
    
    -- Token Validation
    issuer_url              VARCHAR(500),
    jwks_url                VARCHAR(500),
    
    -- Callback Configuration (optional)
    cdex_callback_url       VARCHAR(500),
    subscription_webhook_url VARCHAR(500),
    
    -- Contact Information
    business_contact_name   VARCHAR(255),
    business_contact_email  VARCHAR(255),
    technical_contact_name  VARCHAR(255),
    technical_contact_email VARCHAR(255),
    security_contact_email  VARCHAR(255),
    
    -- Environment
    environment             VARCHAR(20) DEFAULT 'dev',      -- dev, perf, prod
    
    -- Audit Fields
    registered_at           TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_auth_at            TIMESTAMP WITH TIME ZONE,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    notes                   TEXT
);

-- Indexes for common lookups
CREATE INDEX IF NOT EXISTS idx_oauth_provider_tenant 
    ON pagw.oauth_provider_registry(tenant);
    
CREATE INDEX IF NOT EXISTS idx_oauth_provider_status 
    ON pagw.oauth_provider_registry(status) 
    WHERE status = 'ACTIVE';
    
CREATE INDEX IF NOT EXISTS idx_oauth_provider_environment 
    ON pagw.oauth_provider_registry(environment);

-- GIN index for array searches (find provider by NPI or TIN)
CREATE INDEX IF NOT EXISTS idx_oauth_provider_npis 
    ON pagw.oauth_provider_registry USING GIN(npis);
    
CREATE INDEX IF NOT EXISTS idx_oauth_provider_tins 
    ON pagw.oauth_provider_registry USING GIN(tins);

-- Trigger for updated_at
DROP TRIGGER IF EXISTS oauth_provider_registry_updated_at ON pagw.oauth_provider_registry;
CREATE TRIGGER oauth_provider_registry_updated_at
    BEFORE UPDATE ON pagw.oauth_provider_registry
    FOR EACH ROW
    EXECUTE FUNCTION pagw.update_updated_at();

-- ============================================================================
-- 2. SEED DATA - Test provider registrations for local development
-- ============================================================================
INSERT INTO pagw.oauth_provider_registry (
    client_id, 
    tenant, 
    entity_name, 
    entity_type, 
    npis, 
    tins,
    status, 
    allowed_apis,
    rate_limit_per_minute,
    issuer_url,
    environment,
    notes
) VALUES 
    -- Test provider from TotalView introspection example
    (
        'bdda3ee5-df8f-4c86-8d89-4a160e90764d',
        'carelon',
        'Test Provider Organization',
        'PROVIDER',
        ARRAY['1234567890', '9876543210'],
        ARRAY['12-3456789'],
        'ACTIVE',
        ARRAY['PAS_SUBMIT', 'PAS_INQUIRE', 'CDEX_SUBMIT_ATTACHMENT', 'PAS_SUBSCRIBE'],
        100,
        'https://dev.totalview.healthos.carelon.com',
        'dev',
        'Test provider from TotalView introspection response'
    ),
    
    -- Elevance test provider
    (
        'elevance-test-client-001',
        'elevance',
        'Elevance Test EHR System',
        'EHR',
        ARRAY['1111111111', '2222222222'],
        ARRAY['11-1111111'],
        'ACTIVE',
        ARRAY['PAS_SUBMIT', 'PAS_INQUIRE'],
        200,
        'https://dev.totalview.healthos.carelon.com',
        'dev',
        'Elevance test provider requiring ACMP mapping'
    ),
    
    -- Clearinghouse test
    (
        'clearinghouse-test-001',
        'carelon',
        'Test Clearinghouse Inc',
        'CLEARINGHOUSE',
        ARRAY['3333333333', '4444444444', '5555555555'],
        ARRAY['33-3333333'],
        'ACTIVE',
        ARRAY['PAS_SUBMIT', 'PAS_INQUIRE', 'PAS_SUBSCRIBE'],
        500,
        'https://dev.totalview.healthos.carelon.com',
        'dev',
        'Multi-provider clearinghouse'
    )
ON CONFLICT (client_id) DO NOTHING;

-- ============================================================================
-- 3. COMMENTS
-- ============================================================================
COMMENT ON TABLE pagw.oauth_provider_registry IS 
    'OAuth provider registration and entitlements for API Gateway authorization';

COMMENT ON COLUMN pagw.oauth_provider_registry.client_id IS 
    'OAuth clientId from TotalView token introspection (primary lookup key)';

COMMENT ON COLUMN pagw.oauth_provider_registry.tenant IS 
    'Source of truth for routing: elevance (ACMP mapping) vs carelon (direct)';

COMMENT ON COLUMN pagw.oauth_provider_registry.allowed_apis IS 
    'Array of allowed PAS capabilities: PAS_SUBMIT, PAS_INQUIRE, CDEX_SUBMIT_ATTACHMENT, PAS_SUBSCRIBE';

COMMENT ON COLUMN pagw.oauth_provider_registry.npis IS 
    'Array of NPIs - provider can have multiple (individual + group + facility)';

COMMENT ON COLUMN pagw.oauth_provider_registry.status IS 
    'ACTIVE = allowed to call APIs, SUSPENDED = temporary block, REVOKED = permanent block';

-- ============================================================================
-- 4. VIEW - Combine OAuth registration with NPI-to-payer routing
-- ============================================================================
-- This view shows which payers each OAuth client can route to based on their NPIs
CREATE OR REPLACE VIEW pagw.v_oauth_provider_routing AS
SELECT 
    opr.client_id,
    opr.tenant,
    opr.entity_name,
    opr.entity_type,
    opr.status AS oauth_status,
    opr.allowed_apis,
    unnest(opr.npis) AS provider_npi,
    pr.payer_id,
    pr.payer_name,
    pr.endpoint_url,
    pr.supports_sync,
    pr.supports_attachments,
    pr.is_active AS payer_route_active
FROM pagw.oauth_provider_registry opr
CROSS JOIN LATERAL unnest(opr.npis) AS npi
LEFT JOIN pagw.provider_registry pr 
    ON pr.provider_npi = npi
WHERE opr.status = 'ACTIVE';

COMMENT ON VIEW pagw.v_oauth_provider_routing IS 
    'Shows payer routing options for each active OAuth client based on their registered NPIs';
