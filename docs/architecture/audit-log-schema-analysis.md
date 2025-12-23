# Audit Log Schema Analysis

## Current State - Schema Mismatch

### V001 Migration Schema (Database)
**Purpose**: Compliance audit trail (NO PHI according to comments)

| Column | Type | Purpose |
|--------|------|---------|
| id | BIGSERIAL | Primary key |
| event_id | VARCHAR(100) | Event identifier |
| event_type | VARCHAR(50) | Type of event (CREATE, UPDATE, ACCESS, etc.) |
| event_timestamp | TIMESTAMP WITH TIME ZONE | When event occurred |
| pagw_id | VARCHAR(50) | PAGW request identifier |
| correlation_id | VARCHAR(100) | Correlation for tracing |
| tenant | VARCHAR(100) | Tenant identifier |
| service_name | VARCHAR(50) | Service that generated event |
| user_id | VARCHAR(100) | User/actor identifier |
| action | VARCHAR(100) | Action performed |
| resource_type | VARCHAR(50) | Type of resource |
| outcome | VARCHAR(20) | success, failure, error |
| error_code | VARCHAR(50) | Error code if applicable |
| metadata | JSONB | Additional context (NO PHI) |
| created_at | TIMESTAMP WITH TIME ZONE | Record creation time |

### AuditService.java Expected Schema (Code)
**Purpose**: HIPAA-compliant audit trail WITH PHI tracking

| Column | Purpose in Code |
|--------|-----------------|
| id | UUID primary key |
| event_timestamp | When event occurred (NOW()) |
| event_type | Type of event (CREATE, UPDATE, ACCESS, etc.) |
| **event_source** | Service generating event (pasorchestrator, pagwcore, etc.) |
| **actor_id** | User/service performing action |
| **actor_type** | Type of actor (USER, SERVICE, SYSTEM) |
| **actor_ip** | IP address of actor |
| resource_type | Type of resource (REQUEST, etc.) |
| **resource_id** | Specific resource identifier (PAGW ID) |
| **action_description** | Detailed action description |
| correlation_id | Correlation for tracing |
| **trace_id** | Distributed tracing ID |
| **request_path** | HTTP request path |
| **phi_accessed** | Boolean - was PHI accessed? |
| **phi_fields_accessed** | Array of PHI field names accessed |
| **access_reason** | Business justification for PHI access |

**Bolded columns** = Missing from V001 schema

## Column Mapping Analysis

### Direct Mappings (Same Purpose, Different Name)
| V001 Schema | AuditService Code | Mapping |
|-------------|-------------------|---------|
| service_name | event_source | ✅ Same purpose - which service |
| user_id | actor_id | ✅ Same purpose - who performed action |
| pagw_id | resource_id | ✅ Same purpose - what resource |
| action | action_description | ✅ Same purpose - what action |

### New Columns Required for HIPAA/PHI Compliance
| Column | Needed? | Justification |
|--------|---------|---------------|
| actor_type | ✅ CRITICAL | Distinguish USER vs SERVICE vs SYSTEM access (HIPAA requirement) |
| actor_ip | ✅ CRITICAL | Track source of access (HIPAA requirement) |
| trace_id | ✅ RECOMMENDED | Distributed tracing (observability best practice) |
| request_path | ✅ RECOMMENDED | API endpoint tracking |
| phi_accessed | ✅ CRITICAL | HIPAA requirement - flag PHI access events |
| phi_fields_accessed | ✅ CRITICAL | HIPAA requirement - which PHI fields touched |
| access_reason | ✅ CRITICAL | HIPAA requirement - business justification |

### Columns to Deprecate/Remove
| Column | Status | Recommendation |
|--------|--------|----------------|
| event_id | REDUNDANT | Use id (UUID) instead |
| tenant | KEEP | Multi-tenancy support |
| outcome | KEEP | Important for error tracking |
| error_code | KEEP | Important for error tracking |
| metadata | KEEP | Flexible JSONB for additional context |

## Recommended Unified Schema

```sql
CREATE TABLE IF NOT EXISTS pagw.audit_log (
    -- Primary key
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event identification
    event_type              VARCHAR(50) NOT NULL,           -- CREATE, UPDATE, ACCESS, EXPORT, etc.
    event_timestamp         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_source            VARCHAR(50) NOT NULL,           -- service name (pasorchestrator, pagwcore, etc.)
    
    -- Actor tracking (HIPAA requirement)
    actor_id                VARCHAR(100) NOT NULL,          -- user or service identifier
    actor_type              VARCHAR(20) NOT NULL,           -- USER, SERVICE, SYSTEM
    actor_ip                VARCHAR(50),                    -- IP address if available
    
    -- Resource tracking
    resource_type           VARCHAR(50) NOT NULL,           -- REQUEST, PROVIDER, PAYER, etc.
    resource_id             VARCHAR(100) NOT NULL,          -- PAGW ID or other resource identifier
    
    -- Action details
    action_description      TEXT NOT NULL,                  -- Detailed description
    outcome                 VARCHAR(20),                    -- success, failure, error
    error_code              VARCHAR(50),                    -- Error code if applicable
    
    -- Tracing and context
    correlation_id          VARCHAR(100),                   -- Request correlation ID
    trace_id                VARCHAR(100),                   -- Distributed trace ID
    request_path            VARCHAR(500),                   -- API endpoint
    tenant                  VARCHAR(100),                   -- Multi-tenancy support
    
    -- PHI access tracking (HIPAA compliance)
    phi_accessed            BOOLEAN NOT NULL DEFAULT false, -- Was PHI accessed?
    phi_fields_accessed     TEXT[],                         -- Array of PHI field names
    access_reason           VARCHAR(500),                   -- Business justification
    
    -- Additional context
    metadata                JSONB,                          -- Flexible additional data
    
    -- Timestamps
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON pagw.audit_log(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource ON pagw.audit_log(resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_type ON pagw.audit_log(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_log_correlation ON pagw.audit_log(correlation_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON pagw.audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_phi ON pagw.audit_log(phi_accessed) WHERE phi_accessed = true;
```

## Implementation Strategy

### Option 1: V002 Migration (ALTER TABLE) - RECOMMENDED
**Pros**: 
- No data loss
- Preserves existing audit records
- Safer for production

**Cons**: 
- More complex migration
- Requires column renaming

### Option 2: Replace V001 (DROP/CREATE) - SIMPLER
**Pros**: 
- Clean slate
- Simpler migration

**Cons**: 
- Loses existing audit data
- Requires FlywayResetConfig

### Option 3: Update AuditService to use V001 schema
**Pros**: 
- No migration needed

**Cons**: 
- Loses HIPAA-required PHI tracking
- Missing critical compliance fields
- **NOT RECOMMENDED** - compliance risk

## Recommendation

**Use Option 1 (V002 Migration)** with the following approach:

1. Create V002__update_audit_log_schema.sql
2. Rename columns: service_name → event_source, user_id → actor_id, pagw_id → resource_id, action → action_description
3. Add new columns: actor_type, actor_ip, trace_id, request_path, phi_accessed, phi_fields_accessed, access_reason
4. Drop redundant column: event_id
5. Change id from BIGSERIAL to UUID (alter type)
6. Update indexes

This maintains compliance requirements while preserving existing audit trail.
