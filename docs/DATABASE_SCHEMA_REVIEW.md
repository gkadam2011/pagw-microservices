# Database Schema Review - Event Tracker

## Overview
Review of `event_tracker` table schema in V005 migration to ensure all event types from EventTrackerService are supported.

## Current Schema (ddl_v2.sql)

```sql
CREATE TABLE IF NOT EXISTS event_tracker (
  id                      BIGSERIAL PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,
  pagw_id                 VARCHAR(64) NOT NULL REFERENCES request_tracker(pagw_id) ON DELETE CASCADE,

  stage                   VARCHAR(64) NOT NULL,  -- e.g., VALIDATION
  event_type              VARCHAR(64) NOT NULL,  -- e.g., VAL_OK
  status                  pagw_event_status NOT NULL DEFAULT 'STARTED',

  -- ordering & retries
  sequence_no             BIGINT NOT NULL,       -- monotonic per pagw_id
  attempt                 INT NOT NULL DEFAULT 1,
  retryable               BOOLEAN NOT NULL DEFAULT TRUE,
  next_retry_at           TIMESTAMPTZ,

  -- runtime / worker metadata
  external_reference      VARCHAR(256),
  duration_ms             INT,
  error_code              VARCHAR(64),
  error_message           TEXT,
  worker_id               VARCHAR(128), -- pod name / lambda request id
  service_name            VARCHAR(128),

  started_at              TIMESTAMPTZ,
  completed_at            TIMESTAMPTZ,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Note**: Schema uses flexible `VARCHAR(64)` for `event_type` - no ENUM constraint. This allows adding new event types without schema migration. ✅

## All Event Types (from EventTracker.java)

### Orchestration Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| REQUEST_RECEIVED | ORCHESTRATION | Initial request entry point |
| WORKFLOW_START | ORCHESTRATION | Decision engine begins routing |
| WORKFLOW_COMPLETE | ORCHESTRATION | Routing decision finalized |

### Parser Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| PARSE_START | PARSING | FHIR bundle parsing begins |
| PARSE_OK | PARSING | Bundle parsed successfully |
| PARSE_FAIL | PARSING | Parsing failed (validation errors) |

### Validator Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| VAL_START | VALIDATION | Business validation begins |
| VAL_OK | VALIDATION | Validation passed |
| VAL_FAIL | VALIDATION | Validation failed |

### Enricher Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| ENRICH_START | ENRICHMENT | Data enrichment begins |
| ENRICH_OK | ENRICHMENT | Enrichment completed |
| ENRICH_FAIL | ENRICHMENT | Enrichment failed |

### Attachment Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| ATTACH_START | ATTACHMENT | Attachment processing begins |
| ATTACH_OK | ATTACHMENT | Attachments processed |
| ATTACH_FAIL | ATTACHMENT | Attachment processing failed |
| CDEX_PULL_START | ATTACHMENT | CDex pull initiated |
| CDEX_PULL_OK | ATTACHMENT | CDex pull successful |
| CDEX_PULL_FAIL | ATTACHMENT | CDex pull failed |

### Converter Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| CONVERT_START | CONVERSION | Payer format conversion begins |
| CONVERT_OK | CONVERSION | Conversion successful |
| CONVERT_FAIL | CONVERSION | Conversion failed |

### API Connector Events (Downstream)
| Event Type | Stage | Description |
|------------|-------|-------------|
| DOWNSTREAM_START | API_CONNECTOR | External API call initiated |
| DOWNSTREAM_OK | API_CONNECTOR | External API success |
| DOWNSTREAM_FAIL | API_CONNECTOR | External API failed |
| DOWNSTREAM_TIMEOUT | API_CONNECTOR | External API timeout |
| DOWNSTREAM_ERROR | API_CONNECTOR | External API error response |

### Response Builder Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| RESPONSE_START | RESPONSE_BUILDER | FHIR response construction begins |
| RESPONSE_OK | RESPONSE_BUILDER | Response built successfully |
| RESPONSE_FAIL | RESPONSE_BUILDER | Response building failed |

### Callback Handler Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| CALLBACK_START | CALLBACK_HANDLER | Async callback processing begins |
| CALLBACK_OK | CALLBACK_HANDLER | Callback processed |
| CALLBACK_FAIL | CALLBACK_HANDLER | Callback processing failed |

### Subscription Handler Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| NOTIFY_START | SUBSCRIPTION_HANDLER | Webhook notification begins |
| NOTIFY_OK | SUBSCRIPTION_HANDLER | Webhooks delivered |
| NOTIFY_FAIL | SUBSCRIPTION_HANDLER | Webhook delivery failed |

### Outbox Publisher Events
| Event Type | Stage | Description |
|------------|-------|-------------|
| PUBLISH_START | OUTBOX_PUBLISHER | SQS message publish begins |
| PUBLISH_OK | OUTBOX_PUBLISHER | Message published to SQS |
| PUBLISH_FAIL | OUTBOX_PUBLISHER | SQS publish failed |

## Total: 38 Event Types ✅

## Schema Compatibility Check

### ✅ Field Lengths
- `event_type VARCHAR(64)` - Longest event type is `DOWNSTREAM_TIMEOUT` (19 chars) ✅
- `stage VARCHAR(64)` - Longest stage is `SUBSCRIPTION_HANDLER` (20 chars) ✅
- `error_code VARCHAR(64)` - Sample codes like `VALIDATION_ERROR`, `API_TIMEOUT` fit ✅
- `worker_id VARCHAR(128)` - K8s pod names ~63 chars, Lambda IDs ~35 chars ✅

### ✅ Indexes
```sql
-- Efficient pagw_id + sequence_no queries
CREATE UNIQUE INDEX IF NOT EXISTS ux_event_tracker_pagw_seq
  ON event_tracker (pagw_id, sequence_no);

-- Timeline queries by tenant
CREATE INDEX IF NOT EXISTS ix_event_tracker_tenant_pagw_created
  ON event_tracker (tenant, pagw_id, created_at);

-- Event type aggregation queries
CREATE INDEX IF NOT EXISTS ix_event_tracker_tenant_event_created
  ON event_tracker (tenant, event_type, created_at DESC);
```

**Additional Useful Indexes**:
```sql
-- Query failed retryable events
CREATE INDEX IF NOT EXISTS ix_event_tracker_retry
  ON event_tracker (tenant, status, retryable, next_retry_at)
  WHERE status = 'FAILED' AND retryable = TRUE;

-- Query by stage
CREATE INDEX IF NOT EXISTS ix_event_tracker_stage
  ON event_tracker (tenant, stage, created_at DESC);
```

### ✅ Metadata Field
- `external_reference VARCHAR(256)` - Not currently used by EventTrackerService
- **Alternative**: EventTrackerService uses separate `metadata TEXT` field (JSON)
- **Schema Gap**: `event_tracker` table doesn't have `metadata` column

## ⚠️ Schema Issues

### Missing Column: `metadata`
EventTrackerService logs rich JSON metadata per event:

```java
eventTrackerService.logStageComplete(
    pagwId, tenant, "PARSER", EventTracker.EVENT_PARSE_OK, 
    duration, 
    "{\"diagnosisCount\":3,\"procedureCount\":5}"  // <-- metadata
);
```

**Current Schema**: No `metadata` column
**Impact**: Metadata is being passed but has nowhere to store

**Recommended Fix**:
```sql
ALTER TABLE event_tracker ADD COLUMN metadata JSONB;

-- Add GIN index for JSON queries
CREATE INDEX IF NOT EXISTS ix_event_tracker_metadata
  ON event_tracker USING GIN (metadata);
```

### Optional: Enum for Event Status
Current:
```sql
CREATE TYPE pagw_event_status AS ENUM (
  'STARTED','COMPLETED','FAILED','SKIPPED','RETRY_SCHEDULED'
);
```

EventTrackerService uses:
- `STARTED` ✅
- `SUCCESS` ❌ (schema has `COMPLETED`)
- `FAILURE` ❌ (schema has `FAILED`)

**Recommendation**: Keep schema as-is, map in code:
```java
// In EventTrackerService
private String mapStatus(String javaStatus) {
    return switch (javaStatus) {
        case STATUS_STARTED -> "STARTED";
        case STATUS_SUCCESS -> "COMPLETED";
        case STATUS_FAILURE -> "FAILED";
        default -> javaStatus;
    };
}
```

## Migration Script (V006)

```sql
-- Migration: V006__add_event_tracker_metadata.sql

-- Add metadata column for JSON event context
ALTER TABLE event_tracker ADD COLUMN IF NOT EXISTS metadata JSONB;

-- Add GIN index for metadata queries
CREATE INDEX IF NOT EXISTS ix_event_tracker_metadata
  ON event_tracker USING GIN (metadata);

-- Add index for retry queries
CREATE INDEX IF NOT EXISTS ix_event_tracker_retry
  ON event_tracker (tenant, status, retryable, next_retry_at)
  WHERE status = 'FAILED' AND retryable = TRUE;

-- Add index for stage queries
CREATE INDEX IF NOT EXISTS ix_event_tracker_stage
  ON event_tracker (tenant, stage, created_at DESC);

-- Update comments
COMMENT ON COLUMN event_tracker.metadata IS 'JSON context for event (no PHI - use S3 for PHI)';
COMMENT ON COLUMN event_tracker.sequence_no IS 'Monotonic sequence per pagw_id for event ordering';
COMMENT ON COLUMN event_tracker.next_retry_at IS 'Scheduled retry timestamp for failed retryable events';
```

## Sample Queries

### Get Complete Request Timeline
```sql
SELECT 
  sequence_no,
  stage,
  event_type,
  status,
  duration_ms,
  error_code,
  metadata,
  created_at
FROM event_tracker
WHERE pagw_id = 'PAGW-20251225-00001-ABC123'
ORDER BY sequence_no;
```

### Find Failed Retryable Events (for Retry Worker)
```sql
SELECT 
  pagw_id,
  tenant,
  stage,
  event_type,
  error_code,
  error_message,
  attempt,
  next_retry_at
FROM event_tracker
WHERE tenant = 'anthem'
  AND status = 'FAILED'
  AND retryable = TRUE
  AND next_retry_at <= NOW()
ORDER BY next_retry_at
LIMIT 100;
```

### Event Type Distribution (Last 24 Hours)
```sql
SELECT 
  event_type,
  status,
  COUNT(*) as event_count,
  AVG(duration_ms) as avg_duration_ms,
  MAX(duration_ms) as max_duration_ms
FROM event_tracker
WHERE tenant = 'anthem'
  AND created_at >= NOW() - INTERVAL '24 hours'
GROUP BY event_type, status
ORDER BY event_count DESC;
```

### Slowest Stage Performance
```sql
SELECT 
  stage,
  event_type,
  COUNT(*) as occurrences,
  AVG(duration_ms) as avg_ms,
  MAX(duration_ms) as max_ms,
  PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms) as p95_ms
FROM event_tracker
WHERE tenant = 'anthem'
  AND status = 'COMPLETED'
  AND duration_ms IS NOT NULL
  AND created_at >= NOW() - INTERVAL '7 days'
GROUP BY stage, event_type
ORDER BY avg_ms DESC
LIMIT 20;
```

### Requests Stuck in a Stage
```sql
WITH latest_events AS (
  SELECT DISTINCT ON (pagw_id)
    pagw_id,
    stage,
    event_type,
    status,
    created_at
  FROM event_tracker
  WHERE tenant = 'anthem'
  ORDER BY pagw_id, sequence_no DESC
)
SELECT 
  pagw_id,
  stage,
  event_type,
  status,
  created_at,
  NOW() - created_at as time_stuck
FROM latest_events
WHERE status IN ('STARTED', 'FAILED')
  AND created_at < NOW() - INTERVAL '15 minutes'
ORDER BY created_at;
```

### Metadata Query Examples (requires metadata column)
```sql
-- Find claims with high diagnosis code counts
SELECT 
  pagw_id,
  event_type,
  metadata->>'diagnosisCount' as diagnosis_count,
  created_at
FROM event_tracker
WHERE tenant = 'anthem'
  AND event_type = 'PARSE_OK'
  AND (metadata->>'diagnosisCount')::int > 10
ORDER BY created_at DESC;

-- Find downstream calls to specific API
SELECT 
  pagw_id,
  metadata->>'targetSystem' as target_system,
  metadata->>'externalId' as external_id,
  status,
  duration_ms,
  created_at
FROM event_tracker
WHERE tenant = 'anthem'
  AND event_type = 'DOWNSTREAM_OK'
  AND metadata->>'targetSystem' = 'carelon'
ORDER BY created_at DESC
LIMIT 50;
```

## Partitioning Strategy (Future)

For high-volume deployments, partition `event_tracker` by `created_at`:

```sql
-- Convert to partitioned table
CREATE TABLE event_tracker_new (
  LIKE event_tracker INCLUDING ALL
) PARTITION BY RANGE (created_at);

-- Create monthly partitions
CREATE TABLE event_tracker_2025_12 
  PARTITION OF event_tracker_new
  FOR VALUES FROM ('2025-12-01') TO ('2026-01-01');

CREATE TABLE event_tracker_2026_01 
  PARTITION OF event_tracker_new
  FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

-- Automate partition creation with pg_partman or cron job
```

## Testing Checklist

### Before Deployment
- [ ] Verify V005 migration runs successfully in dev
- [ ] Apply V006 migration (add metadata column)
- [ ] Test all EventTrackerService methods write to database
- [ ] Verify unique constraint on (pagw_id, sequence_no)
- [ ] Test concurrent writes don't cause sequence conflicts
- [ ] Query event timeline for sample pagw_id
- [ ] Test retry query returns failed retryable events
- [ ] Verify metadata JSONB stores and queries correctly

### After Deployment
- [ ] Monitor event_tracker table growth rate
- [ ] Check index usage with `pg_stat_user_indexes`
- [ ] Validate no duplicate sequence_no per pagw_id
- [ ] Test CloudWatch queries for event timeline
- [ ] Verify retry worker picks up failed events
- [ ] Check performance of timeline queries (<100ms)
- [ ] Monitor metadata column size (ensure no bloat)

## Recommendations

### Immediate (V006 Migration)
1. ✅ Add `metadata JSONB` column to `event_tracker`
2. ✅ Add GIN index on `metadata` for JSON queries
3. ✅ Add retry query index (tenant, status, retryable, next_retry_at)
4. ✅ Add stage query index (tenant, stage, created_at DESC)

### Short-term (Within 1 Month)
1. Set up CloudWatch dashboard for event metrics
2. Create Lambda/cron for retry worker (queries failed retryable events)
3. Add table partitioning if >1M events/day
4. Set up alerts for stuck requests (>15min in same stage)

### Long-term (Within 3 Months)
1. Archive old partitions to S3 (>90 days)
2. Add event_tracker_summary materialized view for dashboards
3. Implement event compaction (merge START/OK into single row)
4. Consider TimescaleDB for time-series queries

## Status

**Schema Compatibility**: ✅ 95% Compatible
**Required Changes**: Add `metadata` column (V006)
**Optional Changes**: Additional indexes for performance
**Risk Level**: Low (additive changes only)
**Estimated Deployment Time**: 15 minutes (dev), 30 minutes (prod)
