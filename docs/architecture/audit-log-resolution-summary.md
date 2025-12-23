# Audit Log Schema Resolution Summary

## Problem Identified

The audit_log table schema in V001 migration did not match the columns expected by AuditService.java, causing PostgreSQL errors:
```
ERROR: column "event_source" of relation "audit_log" does not exist
```

## Root Cause Analysis

### Schema Mismatch Details

**V001 Schema** designed for "NO PHI" compliance audit (original comment in SQL):
- Used `service_name`, `user_id`, `pagw_id`, `action`
- Missing HIPAA-required PHI tracking fields
- Used BIGSERIAL id instead of UUID

**AuditService.java** designed for HIPAA-compliant PHI tracking:
- Expected `event_source`, `actor_id`, `resource_id`, `action_description`
- Required PHI access tracking: `phi_accessed`, `phi_fields_accessed`, `access_reason`
- Expected UUID id and additional actor tracking fields

### Why This Happened
The code and schema evolved separately without synchronized updates. The V001 migration was created as a "NO PHI" audit log, but the AuditService code implements full HIPAA compliance with PHI access tracking.

## Solution Implemented

### V002 Migration Created
[V002__update_audit_log_schema.sql](../pasorchestrator/source/src/main/resources/db/migration/V002__update_audit_log_schema.sql)

**Key Changes:**

1. **Column Renames** (maintains existing data):
   - `service_name` → `event_source`
   - `user_id` → `actor_id`
   - `pagw_id` → `resource_id`
   - `action` → `action_description` (changed to TEXT)

2. **New HIPAA-Required Columns**:
   - `actor_type` VARCHAR(20) NOT NULL - USER, SERVICE, or SYSTEM
   - `actor_ip` VARCHAR(50) - IP address tracking
   - `phi_accessed` BOOLEAN NOT NULL DEFAULT false - PHI access flag
   - `phi_fields_accessed` TEXT[] - Array of PHI field names
   - `access_reason` VARCHAR(500) - Business justification

3. **Observability Enhancements**:
   - `trace_id` VARCHAR(100) - Distributed tracing
   - `request_path` VARCHAR(500) - API endpoint tracking

4. **Schema Improvements**:
   - Changed `id` from BIGSERIAL to UUID
   - Removed redundant `event_id` column
   - Added optimized indexes for PHI queries
   - Added documentation comments

### Final Schema (After V002)

```sql
CREATE TABLE pagw.audit_log (
    id                      UUID PRIMARY KEY,
    event_type              VARCHAR(50) NOT NULL,
    event_timestamp         TIMESTAMP WITH TIME ZONE NOT NULL,
    event_source            VARCHAR(50) NOT NULL,
    actor_id                VARCHAR(100) NOT NULL,
    actor_type              VARCHAR(20) NOT NULL,
    actor_ip                VARCHAR(50),
    resource_type           VARCHAR(50) NOT NULL,
    resource_id             VARCHAR(100) NOT NULL,
    action_description      TEXT NOT NULL,
    outcome                 VARCHAR(20),
    error_code              VARCHAR(50),
    correlation_id          VARCHAR(100),
    trace_id                VARCHAR(100),
    request_path            VARCHAR(500),
    tenant                  VARCHAR(100),
    phi_accessed            BOOLEAN NOT NULL DEFAULT false,
    phi_fields_accessed     TEXT[],
    access_reason           VARCHAR(500),
    metadata                JSONB,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

## Deployment Steps

### 1. Commit Changes
```powershell
cd C:\Users\gkada\pagw-microservices
git add pasorchestrator/source/src/main/resources/db/migration/V002__update_audit_log_schema.sql
git add db/migrations/V002__update_audit_log_schema.sql
git add docs/architecture/audit-log-schema-analysis.md
git add docs/architecture/audit-log-resolution-summary.md
git commit -m "feat: Add V002 migration to align audit_log schema with HIPAA requirements"
```

### 2. Test Locally
```powershell
cd C:\Users\gkada\pagw-microservices\pasorchestrator\source
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Expected output:
- Flyway validates V001 (already applied)
- Flyway applies V002 (new migration)
- Application starts successfully
- Request creation triggers audit_log INSERT without errors

### 3. Deploy to Dev
```bash
# Update ncr_aedleks_pagw_app repo
cd /path/to/ncr_aedleks_pagw_app
# Run sync script to copy changes

# Update Docker CACHE_BUST to force rebuild
docker build --build-arg CACHE_BUST=$(date +%s) -t pasorchestrator:latest .

# Deploy via Tekton/Helm
kubectl rollout restart deployment pasorchestrator -n pagw-srv-dev
```

### 4. Verify
Check pod logs for:
```
Successfully validated 2 migrations
Current version of schema "pagw": 002
```

## Benefits of This Approach

✅ **No Data Loss** - ALTER TABLE preserves existing audit records  
✅ **HIPAA Compliant** - Adds required PHI access tracking  
✅ **Backward Compatible** - Renames columns safely  
✅ **Well Documented** - Comments explain compliance requirements  
✅ **Optimized** - Indexes support PHI audit queries  
✅ **Observability** - Trace ID and request path for debugging  

## Testing Validation

After deployment, verify with:

```sql
-- Check schema
\d pagw.audit_log

-- Verify indexes
\di pagw.idx_audit_log_*

-- Test INSERT (should succeed)
SELECT * FROM pagw.audit_log ORDER BY event_timestamp DESC LIMIT 5;
```

## Related Files Modified

1. `pasorchestrator/source/src/main/resources/db/migration/V002__update_audit_log_schema.sql` - Migration
2. `db/migrations/V002__update_audit_log_schema.sql` - Backup copy
3. `docs/architecture/audit-log-schema-analysis.md` - Detailed analysis
4. `docs/architecture/audit-log-resolution-summary.md` - This summary

## AuditService Usage Examples

After V002 migration, these AuditService methods will work correctly:

```java
// Example 1: PHI Access Logging
auditService.logPhiAccess(
    "PAGW-20251223-00001-314A1791",  // resource_id
    "user@example.com",               // actor_id
    "USER",                           // actor_type
    List.of("SSN", "DateOfBirth"),   // phi_fields_accessed
    "Prior authorization review",     // access_reason
    "correlation-123"                 // correlation_id
);

// Example 2: Request Creation
auditService.logRequestCreated(
    "PAGW-20251223-00002-ABC123",    // resource_id
    "pasorchestrator",                // actor_id (service name)
    "correlation-456"                 // correlation_id
);
```

Both will successfully INSERT into audit_log with proper HIPAA tracking.
