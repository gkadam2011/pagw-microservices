-- =====================================================================
-- PAGW (Prior Auth Gateway) - Operational Data Model v2 (Aurora PostgreSQL)
-- Focus: PAS first, extensible to CRD/DTR.
-- =====================================================================
-- Notes:
-- 1) Use UUID for identifiers where possible.
-- 2) Prefer S3 for large payloads; DB stores pointers + metadata.
-- 3) Partition high-volume tables (event_tracker, audit_log) by created_at.
-- 4) Keep "destination" logical in outbox; resolve queue URLs in config.
-- =====================================================================

-- Extensions (optional)
-- CREATE EXTENSION IF NOT EXISTS pgcrypto; -- for gen_random_uuid()

-- -------------------------
-- ENUMS / DOMAIN CHECKS
-- -------------------------
DO $$ BEGIN
  CREATE TYPE pagw_request_status AS ENUM (
    'RECEIVED','VALIDATED','PENDING','IN_PROGRESS','APPROVED','DENIED','FAILED','CANCELLED','EXPIRED'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_stage AS ENUM (
    'SUBMIT_RECEIVED',
    'STRUCTURE_VALIDATION',
    'BUSINESS_VALIDATION',
    'ENRICHMENT',
    'ATTACHMENT_HANDLING',
    'CANONICAL_MAPPING',
    'DOWNSTREAM_DISPATCH',
    'DOWNSTREAM_RESPONSE',
    'RESPONSE_BUILD',
    'CALLBACK_PROCESS',
    'COMPLETE',
    'ERROR'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_outbox_status AS ENUM ('NEW','SENT','FAILED','DEADLETTERED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_attachment_source AS ENUM ('INLINE','CDEX_PULL','PRESIGNED_UPLOAD','EXTERNAL_REF');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_attachment_status AS ENUM ('PENDING','STORED','FAILED','SKIPPED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_audit_actor_type AS ENUM ('PROVIDER','SYSTEM','SERVICE','ADMIN','DOWNSTREAM');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- -------------------------
-- CORE: request_tracker
-- -------------------------
CREATE TABLE IF NOT EXISTS request_tracker (
  pagw_id                 VARCHAR(64) PRIMARY KEY,     -- e.g., PAGW-<uuid> or UUID string
  tenant                  VARCHAR(64) NOT NULL,        -- e.g., elevance|carelon
  lob                     VARCHAR(32),                 -- line of business (MA/Commercial/etc)
  source_system           VARCHAR(64),                 -- provider system / gateway
  request_type            VARCHAR(32) NOT NULL DEFAULT 'PAS',  -- PAS/CRD/DTR etc
  idempotency_key         VARCHAR(128),
  correlation_id          VARCHAR(128),                -- cross-system correlation
  status                  pagw_request_status NOT NULL DEFAULT 'RECEIVED',
  last_stage              pagw_stage,
  next_stage              pagw_stage,

  -- Workflow/scenario provenance (v2 add)
  workflow_id             VARCHAR(128),
  workflow_version        VARCHAR(32),
  scenario_id             VARCHAR(128),
  ruleset_version         VARCHAR(32),
  workflow_hash           VARCHAR(128),                -- sha256 of workflow.json (optional)

  -- Error summary (last)
  last_error_code         VARCHAR(64),
  last_error_msg          TEXT,
  retry_count             INTEGER NOT NULL DEFAULT 0,

  -- Payload pointers
  raw_s3_bucket           VARCHAR(128),
  raw_s3_key              VARCHAR(512),
  enriched_s3_bucket      VARCHAR(128),
  enriched_s3_key         VARCHAR(512),
  final_s3_bucket         VARCHAR(128),
  final_s3_key            VARCHAR(512),

  -- External references (downstream)
  external_request_id     VARCHAR(128),
  external_reference_id   VARCHAR(128),
  payer_id                VARCHAR(64),                 -- Carelon/EAPI/BCBSA routing id
  provider_npi            VARCHAR(32),
  provider_tin            VARCHAR(32),
  provider_name           VARCHAR(256),
  patient_member_id       VARCHAR(64),
  member_id               VARCHAR(64),
  client_id               VARCHAR(64),

  contains_phi            BOOLEAN NOT NULL DEFAULT TRUE,
  phi_encrypted           BOOLEAN NOT NULL DEFAULT TRUE,

  -- Orchestration timing
  sync_processed          BOOLEAN NOT NULL DEFAULT FALSE,
  sync_processed_at       TIMESTAMPTZ,
  async_queued            BOOLEAN NOT NULL DEFAULT FALSE,
  async_queued_at         TIMESTAMPTZ,

  received_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at            TIMESTAMPTZ,
  callback_sent_at        TIMESTAMPTZ,
  expires_at              TIMESTAMPTZ,

  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Update timestamp trigger (optional)
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$ BEGIN
  CREATE TRIGGER trg_request_tracker_updated_at
  BEFORE UPDATE ON request_tracker
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Indexes aligned to typical queries
CREATE INDEX IF NOT EXISTS idx_req_tenant_status_updated
  ON request_tracker (tenant, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_req_tenant_created
  ON request_tracker (tenant, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_req_tenant_payer_status
  ON request_tracker (tenant, payer_id, status);

CREATE INDEX IF NOT EXISTS idx_req_idempotency
  ON request_tracker (tenant, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

-- -------------------------
-- event_tracker (partitioned)
-- -------------------------
-- Partition by created_at monthly (recommended)
CREATE TABLE IF NOT EXISTS event_tracker (
  id                     BIGSERIAL,
  tenant                 VARCHAR(64) NOT NULL,
  pagw_id                VARCHAR(64) NOT NULL,
  stage                  pagw_stage NOT NULL,
  event_type             VARCHAR(64) NOT NULL,      -- e.g., VAL_OK, VAL_FAIL, AUTHN_OK...
  status                 VARCHAR(32) NOT NULL,      -- OK/FAIL/RETRY/STARTED/COMPLETED etc
  external_reference     VARCHAR(128),

  -- v2 add: ordering + retry context
  sequence_no            BIGINT NOT NULL,
  attempt                INTEGER NOT NULL DEFAULT 0,
  retryable              BOOLEAN NOT NULL DEFAULT FALSE,
  next_retry_at          TIMESTAMPTZ,

  duration_ms            INTEGER,
  error_code             VARCHAR(64),
  error_message          TEXT,

  worker_id              VARCHAR(128),              -- pod name / lambda request id
  metadata               JSONB,                     -- freeform safe metadata (no PHI)

  started_at             TIMESTAMPTZ,
  completed_at           TIMESTAMPTZ,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

  PRIMARY KEY (id, created_at),
  CONSTRAINT fk_event_req FOREIGN KEY (pagw_id) REFERENCES request_tracker(pagw_id)
) PARTITION BY RANGE (created_at);

-- Example partition (create monthly via automation)
-- CREATE TABLE event_tracker_2025_12 PARTITION OF event_tracker
--   FOR VALUES FROM ('2025-12-01') TO ('2026-01-01');

CREATE UNIQUE INDEX IF NOT EXISTS uq_event_pagw_seq
  ON event_tracker (tenant, pagw_id, sequence_no, created_at);

CREATE INDEX IF NOT EXISTS idx_event_pagw_created
  ON event_tracker (tenant, pagw_id, created_at);

CREATE INDEX IF NOT EXISTS idx_event_type_created
  ON event_tracker (tenant, event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_event_stage_created
  ON event_tracker (tenant, stage, created_at DESC);

-- -------------------------
-- attachment_tracker
-- -------------------------
CREATE TABLE IF NOT EXISTS attachment_tracker (
  id                   BIGSERIAL PRIMARY KEY,
  tenant               VARCHAR(64) NOT NULL,
  pagw_id              VARCHAR(64) NOT NULL,
  attachment_id        VARCHAR(128),                 -- docRef id / internal id
  attachment_type      VARCHAR(64),                  -- DocumentReference/Binary/etc
  attachment_source    pagw_attachment_source NOT NULL DEFAULT 'INLINE',  -- v2 add
  original_filename    VARCHAR(512),
  content_type         VARCHAR(128),
  size_bytes           BIGINT,

  s3_bucket            VARCHAR(128),
  s3_key               VARCHAR(512),

  status               pagw_attachment_status NOT NULL DEFAULT 'PENDING',
  fetch_attempts       INTEGER NOT NULL DEFAULT 0,
  error_message        TEXT,

  checksum_sha256      VARCHAR(128),                 -- v2: standardize sha256 field name

  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at         TIMESTAMPTZ,

  CONSTRAINT fk_attach_req FOREIGN KEY (pagw_id) REFERENCES request_tracker(pagw_id)
);

CREATE INDEX IF NOT EXISTS idx_attach_pagw
  ON attachment_tracker (tenant, pagw_id);

CREATE INDEX IF NOT EXISTS idx_attach_checksum
  ON attachment_tracker (checksum_sha256)
  WHERE checksum_sha256 IS NOT NULL;

-- -------------------------
-- outbox (reliable publish)
-- -------------------------
CREATE TABLE IF NOT EXISTS outbox (
  id                    BIGSERIAL PRIMARY KEY,
  tenant                VARCHAR(64) NOT NULL,
  aggregate_type        VARCHAR(64) NOT NULL,         -- REQUEST / ATTACHMENT / etc
  aggregate_id          VARCHAR(128) NOT NULL,        -- usually pagw_id
  event_type            VARCHAR(64) NOT NULL,

  -- v2: logical destination (NOT env-specific queue url)
  destination           VARCHAR(128) NOT NULL,        -- e.g., PAS_REQUEST_VALIDATOR
  destination_type      VARCHAR(32) NOT NULL DEFAULT 'SQS',

  payload               JSONB NOT NULL,               -- no PHI; references only
  status                pagw_outbox_status NOT NULL DEFAULT 'NEW',
  retry_count           INTEGER NOT NULL DEFAULT 0,
  max_retries           INTEGER NOT NULL DEFAULT 8,
  last_error            TEXT,

  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at          TIMESTAMPTZ,
  next_retry_at         TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_nextretry
  ON outbox (tenant, status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
  ON outbox (tenant, aggregate_type, aggregate_id);

-- -------------------------
-- idempotency
-- -------------------------
CREATE TABLE IF NOT EXISTS idempotency (
  idempotency_key       VARCHAR(128) NOT NULL,
  tenant                VARCHAR(64) NOT NULL,
  pagw_id               VARCHAR(64),
  request_hash          VARCHAR(128),                 -- sha256 of canonical request (optional)

  response_status       INTEGER,                      -- HTTP status code
  response_s3_bucket    VARCHAR(128),                 -- v2: prefer S3 pointer
  response_s3_key       VARCHAR(512),
  response_hash         VARCHAR(128),                 -- sha256 of response

  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at            TIMESTAMPTZ,

  PRIMARY KEY (tenant, idempotency_key)
);

-- -------------------------
-- subscriptions
-- -------------------------
CREATE TABLE IF NOT EXISTS subscriptions (
  id                    BIGSERIAL PRIMARY KEY,
  tenant                VARCHAR(64) NOT NULL,
  tenant_id             VARCHAR(64),                  -- optional separate numeric/uuid tenant id
  status                VARCHAR(32) NOT NULL,         -- ACTIVE/INACTIVE
  channel_type          VARCHAR(32) NOT NULL,         -- RESTHOOK/MESSAGE/etc
  endpoint              VARCHAR(1024) NOT NULL,
  content_type          VARCHAR(64),
  headers               JSONB,
  filter_criteria       JSONB,
  expires_at            TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_delivered_at     TIMESTAMPTZ,
  failure_count         INTEGER NOT NULL DEFAULT 0,
  last_error            TEXT
);

CREATE INDEX IF NOT EXISTS idx_subs_tenant_status
  ON subscriptions (tenant, status);

-- -------------------------
-- provider_registry
-- -------------------------
CREATE TABLE IF NOT EXISTS provider_registry (
  id                    BIGSERIAL PRIMARY KEY,
  tenant                VARCHAR(64) NOT NULL,
  provider_npi          VARCHAR(32),
  provider_tax_id       VARCHAR(32),
  provider_name         VARCHAR(256),
  payer_id              VARCHAR(64),
  payer_name            VARCHAR(256),
  endpoint_url          VARCHAR(1024),
  api_version           VARCHAR(32),
  auth_type             VARCHAR(32),
  auth_credentials_secret_name VARCHAR(256),

  -- v2: entitlements in jsonb (future-proof)
  allowed_apis          JSONB,                         -- e.g., {"pas":["pas.submit","pas.inquire"],"cdex":["submit-attachment"]}
  is_active             BOOLEAN NOT NULL DEFAULT TRUE,

  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$ BEGIN
  CREATE TRIGGER trg_provider_registry_updated_at
  BEFORE UPDATE ON provider_registry
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS idx_provider_tenant_npi
  ON provider_registry (tenant, provider_npi);

-- -------------------------
-- payer_configuration
-- -------------------------
CREATE TABLE IF NOT EXISTS payer_configuration (
  id                    BIGSERIAL PRIMARY KEY,
  tenant                VARCHAR(64) NOT NULL,
  payer_id              VARCHAR(64) NOT NULL,
  payer_name            VARCHAR(256),
  base_url              VARCHAR(1024),
  auth_endpoint         VARCHAR(1024),
  submit_endpoint       VARCHAR(1024),
  inquire_endpoint      VARCHAR(1024),
  connection_timeout_ms INTEGER NOT NULL DEFAULT 3000,
  read_timeout_ms       INTEGER NOT NULL DEFAULT 15000,
  max_retries           INTEGER NOT NULL DEFAULT 3,
  retry_delay_ms        INTEGER NOT NULL DEFAULT 250,

  supports_x12          BOOLEAN NOT NULL DEFAULT FALSE,
  supports_fhir         BOOLEAN NOT NULL DEFAULT TRUE,
  requires_attachments  BOOLEAN NOT NULL DEFAULT FALSE,
  is_active             BOOLEAN NOT NULL DEFAULT TRUE,

  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

  UNIQUE (tenant, payer_id)
);

DO $$ BEGIN
  CREATE TRIGGER trg_payer_configuration_updated_at
  BEFORE UPDATE ON payer_configuration
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- -------------------------
-- audit_log (partitioned)
-- -------------------------
CREATE TABLE IF NOT EXISTS audit_log (
  id                    BIGSERIAL,
  tenant                VARCHAR(64) NOT NULL,
  event_type            VARCHAR(64) NOT NULL,
  event_timestamp       TIMESTAMPTZ NOT NULL DEFAULT now(),
  resource_id           VARCHAR(128),
  correlation_id        VARCHAR(128),
  trace_id              VARCHAR(128),                 -- pagw_id or external trace
  event_source          VARCHAR(128),                 -- service name
  actor_id              VARCHAR(128),
  actor_type            pagw_audit_actor_type,
  actor_ip              INET,
  request_path          VARCHAR(1024),
  http_method           VARCHAR(16),
  http_status           INTEGER,
  api_name              VARCHAR(64),

  action_description    TEXT,
  resource_type         VARCHAR(64),
  outcome               VARCHAR(32),                  -- ALLOW/DENY/ERROR
  error_code            VARCHAR(64),
  metadata              JSONB,

  phi_accessed          BOOLEAN NOT NULL DEFAULT FALSE,
  phi_fields_accessed   JSONB,
  access_reason         VARCHAR(256),

  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Example partition:
-- CREATE TABLE audit_log_2025_12 PARTITION OF audit_log
--   FOR VALUES FROM ('2025-12-01') TO ('2026-01-01');

CREATE INDEX IF NOT EXISTS idx_audit_tenant_time
  ON audit_log (tenant, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_trace
  ON audit_log (tenant, trace_id, created_at DESC);

-- -------------------------
-- shedlock (if using scheduled jobs / leader election)
-- -------------------------
CREATE TABLE IF NOT EXISTS shedlock (
  name        VARCHAR(64) PRIMARY KEY,
  lock_until  TIMESTAMPTZ NOT NULL,
  locked_at   TIMESTAMPTZ NOT NULL,
  locked_by   VARCHAR(255) NOT NULL
);

-- -------------------------
-- flyway history (Flyway manages this; included here for reference)
-- -------------------------
-- flyway_schema_history created by Flyway.
