-- =====================================================================
-- PAGW Operational Data Model (v2) - Aurora PostgreSQL 15.x compatible
-- Goals:
--  - Tenant isolation everywhere
--  - Workflow/scenario/ruleset versioning
--  - Strong event ordering + retries
--  - Outbox uses logical destination (not physical queue URL)
--  - Idempotency stores response pointers (S3) instead of large blobs
--  - Partition-friendly timestamps
-- =====================================================================

-- Optional: create schema
-- CREATE SCHEMA IF NOT EXISTS pagw;
-- SET search_path TO pagw, public;

-- -----------------------------
-- 1) ENUMs (or use lookup tables)
-- -----------------------------
DO $$ BEGIN
  CREATE TYPE pagw_request_status AS ENUM (
    'RECEIVED',
    'VALIDATED',
    'BUSINESS_VALIDATED',
    'ENRICHED',
    'MAPPED',
    'SENT_DOWNSTREAM',
    'PENDING',
    'APPROVED',
    'DENIED',
    'FAILED',
    'CANCELLED',
    'EXPIRED'
  );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_event_status AS ENUM ('STARTED','COMPLETED','FAILED','SKIPPED','RETRY_SCHEDULED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_attachment_source AS ENUM ('INLINE','CDEX_PULL','PRESIGNED_UPLOAD','DOWNSTREAM_PUSH');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
  CREATE TYPE pagw_outbox_status AS ENUM ('NEW','SENT','FAILED','DEAD');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- -----------------------------
-- 2) request_tracker (system of record)
-- -----------------------------
CREATE TABLE IF NOT EXISTS request_tracker (
  pagw_id                 VARCHAR(64) PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,
  source_system           VARCHAR(64),
  request_type            VARCHAR(64) NOT NULL, -- e.g., PAS_SUBMIT
  idempotency_key         VARCHAR(128),
  correlation_id          VARCHAR(128),

  status                  pagw_request_status NOT NULL DEFAULT 'RECEIVED',
  last_stage              VARCHAR(64),
  next_stage              VARCHAR(64),

  -- workflow/scenario/ruleset versioning
  workflow_id             VARCHAR(128),
  workflow_version        VARCHAR(32),
  scenario_id             VARCHAR(128),
  ruleset_version         VARCHAR(32),
  workflow_hash           VARCHAR(96), -- e.g., sha256 hex

  -- error snapshot
  last_error_code         VARCHAR(64),
  last_error_msg          TEXT,
  retry_count             INT NOT NULL DEFAULT 0,

  -- payload pointers
  raw_s3_bucket           VARCHAR(128),
  raw_s3_key              TEXT,
  enriched_s3_bucket      VARCHAR(128),
  enriched_s3_key         TEXT,
  final_s3_bucket         VARCHAR(128),
  final_s3_key            TEXT,

  -- business identifiers (avoid PHI where possible)
  external_request_id     VARCHAR(128),
  external_reference_id   VARCHAR(128),
  payer_id                VARCHAR(64),
  provider_npi            VARCHAR(32),
  patient_member_id       VARCHAR(128),
  client_id               VARCHAR(128),
  member_id               VARCHAR(128),
  provider_id             VARCHAR(128),

  -- PHI flags
  contains_phi            BOOLEAN NOT NULL DEFAULT FALSE,
  phi_encrypted           BOOLEAN NOT NULL DEFAULT FALSE,

  -- processing mode markers
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

CREATE INDEX IF NOT EXISTS ix_request_tracker_tenant_status_updated
  ON request_tracker (tenant, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS ix_request_tracker_tenant_created
  ON request_tracker (tenant, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_request_tracker_tenant_correlation
  ON request_tracker (tenant, correlation_id);

-- Auto-update updated_at
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ language plpgsql;

DROP TRIGGER IF EXISTS trg_request_tracker_updated_at ON request_tracker;
CREATE TRIGGER trg_request_tracker_updated_at
BEFORE UPDATE ON request_tracker
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- -----------------------------
-- 3) event_tracker (high volume) - partition-ready
-- -----------------------------
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

CREATE UNIQUE INDEX IF NOT EXISTS ux_event_tracker_pagw_seq
  ON event_tracker (pagw_id, sequence_no);

CREATE INDEX IF NOT EXISTS ix_event_tracker_tenant_pagw_created
  ON event_tracker (tenant, pagw_id, created_at);

CREATE INDEX IF NOT EXISTS ix_event_tracker_tenant_event_created
  ON event_tracker (tenant, event_type, created_at DESC);

-- NOTE: For true partitioning, convert to RANGE partitioned table by created_at
-- and create monthly partitions. Kept as single table for v2 starter.

-- -----------------------------
-- 4) attachment_tracker
-- -----------------------------
CREATE TABLE IF NOT EXISTS attachment_tracker (
  id                      BIGSERIAL PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,
  pagw_id                 VARCHAR(64) NOT NULL REFERENCES request_tracker(pagw_id) ON DELETE CASCADE,

  attachment_id           VARCHAR(128), -- DocumentReference.id or generated
  attachment_type         VARCHAR(64),  -- DocumentReference/Binary/Questionnaire
  attachment_source       pagw_attachment_source NOT NULL DEFAULT 'INLINE',

  original_filename       TEXT,
  content_type            VARCHAR(128),
  size_bytes              BIGINT,

  s3_bucket               VARCHAR(128),
  s3_key                  TEXT,

  status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- keep string for flexibility
  fetch_status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  fetch_attempts          INT NOT NULL DEFAULT 0,
  error_message           TEXT,

  checksum_sha256         VARCHAR(96), -- hex

  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at            TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_attachment_tracker_tenant_pagw
  ON attachment_tracker (tenant, pagw_id);

-- -----------------------------
-- 5) outbox (reliable publish)
-- -----------------------------
CREATE TABLE IF NOT EXISTS outbox (
  id                      BIGSERIAL PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,

  aggregate_type          VARCHAR(64) NOT NULL, -- e.g., PAGW_REQUEST
  aggregate_id            VARCHAR(64) NOT NULL, -- pagw_id

  event_type              VARCHAR(64) NOT NULL, -- e.g., VAL_OK
  destination             VARCHAR(64) NOT NULL, -- logical (e.g., PAS_REQUEST_VALIDATOR)
  destination_type        VARCHAR(32) NOT NULL DEFAULT 'SQS',

  payload_json            JSONB NOT NULL,
  status                  pagw_outbox_status NOT NULL DEFAULT 'NEW',

  retry_count             INT NOT NULL DEFAULT 0,
  max_retries             INT NOT NULL DEFAULT 10,
  last_error              TEXT,

  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at            TIMESTAMPTZ,
  next_retry_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_outbox_tenant_status_created
  ON outbox (tenant, status, created_at);

-- -----------------------------
-- 6) idempotency (lean)
-- -----------------------------
CREATE TABLE IF NOT EXISTS idempotency (
  idempotency_key         VARCHAR(128) PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,
  pagw_id                 VARCHAR(64) NOT NULL REFERENCES request_tracker(pagw_id) ON DELETE CASCADE,

  request_hash            VARCHAR(96), -- sha256 of request
  response_status         VARCHAR(32),
  response_s3_bucket      VARCHAR(128),
  response_s3_key         TEXT,
  response_hash           VARCHAR(96),

  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at              TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_idempotency_tenant_pagw
  ON idempotency (tenant, pagw_id);

-- -----------------------------
-- 7) provider_registry (entitlements starter)
-- -----------------------------
CREATE TABLE IF NOT EXISTS provider_registry (
  id                      BIGSERIAL PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,

  provider_npi            VARCHAR(32),
  provider_tax_id         VARCHAR(32),
  provider_name           TEXT,

  payer_id                VARCHAR(64),
  payer_name              TEXT,

  endpoint_url            TEXT,
  api_version             VARCHAR(32),

  auth_type               VARCHAR(32), -- MTLS/JWT/APIKEY/OAUTH
  auth_credentials_secret_name TEXT,

  -- legacy feature flags (ok for v1/v2)
  supports_sync           BOOLEAN NOT NULL DEFAULT TRUE,
  supports_attachments    BOOLEAN NOT NULL DEFAULT TRUE,
  supports_subscriptions  BOOLEAN NOT NULL DEFAULT FALSE,

  -- richer entitlement object (recommended)
  allowed_apis            JSONB, -- { "pas.submit":true, "pas.inquire":true, ... }
  allowed_scopes          JSONB, -- array or map; depends on introspection payload

  is_active               BOOLEAN NOT NULL DEFAULT TRUE,

  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_provider_registry_tenant_npi
  ON provider_registry (tenant, provider_npi);

DROP TRIGGER IF EXISTS trg_provider_registry_updated_at ON provider_registry;
CREATE TRIGGER trg_provider_registry_updated_at
BEFORE UPDATE ON provider_registry
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- -----------------------------
-- 8) payer_configuration
-- -----------------------------
CREATE TABLE IF NOT EXISTS payer_configuration (
  id                      BIGSERIAL PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,
  payer_id                VARCHAR(64) NOT NULL,
  payer_name              TEXT,

  base_url                TEXT,
  auth_endpoint           TEXT,
  submit_endpoint         TEXT,
  inquiry_endpoint        TEXT,

  connection_timeout_ms   INT DEFAULT 5000,
  read_timeout_ms         INT DEFAULT 15000,
  max_retries             INT DEFAULT 2,
  retry_delay_ms          INT DEFAULT 250,

  supports_x12            BOOLEAN NOT NULL DEFAULT FALSE,
  supports_fhir           BOOLEAN NOT NULL DEFAULT TRUE,
  requires_attachments    BOOLEAN NOT NULL DEFAULT FALSE,

  is_active               BOOLEAN NOT NULL DEFAULT TRUE,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_payer_configuration_tenant_payer
  ON payer_configuration (tenant, payer_id);

DROP TRIGGER IF EXISTS trg_payer_configuration_updated_at ON payer_configuration;
CREATE TRIGGER trg_payer_configuration_updated_at
BEFORE UPDATE ON payer_configuration
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- -----------------------------
-- 9) subscriptions
-- -----------------------------
CREATE TABLE IF NOT EXISTS subscriptions (
  id                      BIGSERIAL PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,
  tenant_id               VARCHAR(64),

  status                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  channel_type            VARCHAR(32), -- RESTHOOK/MESSAGE
  endpoint                TEXT,
  content_type            VARCHAR(64),
  headers                 JSONB,
  filter_criteria         JSONB,

  expires_at              TIMESTAMPTZ,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_delivered_at       TIMESTAMPTZ,
  failure_count           INT NOT NULL DEFAULT 0,
  last_error              TEXT
);

CREATE INDEX IF NOT EXISTS ix_subscriptions_tenant_status
  ON subscriptions (tenant, status);

-- -----------------------------
-- 10) audit_log (partition candidate)
-- -----------------------------
CREATE TABLE IF NOT EXISTS audit_log (
  id                      BIGSERIAL PRIMARY KEY,
  tenant                  VARCHAR(64) NOT NULL,

  event_type              VARCHAR(64) NOT NULL,
  event_timestamp         TIMESTAMPTZ NOT NULL DEFAULT now(),

  resource_id             VARCHAR(128),
  correlation_id          VARCHAR(128),
  trace_id                VARCHAR(64),
  pagw_id                 VARCHAR(64),

  actor_id                VARCHAR(128),
  actor_type              VARCHAR(64), -- provider/system/user
  actor_ip                INET,

  api_name                VARCHAR(128),
  http_method             VARCHAR(16),
  http_status             INT,
  request_path            TEXT,

  action_description      TEXT,
  resource_type           VARCHAR(64),
  outcome                 VARCHAR(32),
  error_code              VARCHAR(64),

  metadata                JSONB,

  -- PHI access auditing
  phi_accessed            BOOLEAN NOT NULL DEFAULT FALSE,
  phi_fields_accessed     JSONB,
  access_reason           TEXT,

  created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_audit_log_tenant_created
  ON audit_log (tenant, created_at DESC);

-- -----------------------------
-- 11) shedlock (if you use ShedLock)
-- -----------------------------
CREATE TABLE IF NOT EXISTS shedlock (
  name                    VARCHAR(64) PRIMARY KEY,
  lock_until              TIMESTAMPTZ NOT NULL,
  locked_at               TIMESTAMPTZ NOT NULL,
  locked_by               VARCHAR(255) NOT NULL
);

-- -----------------------------
-- 12) flyway_schema_history is managed by Flyway
-- -----------------------------
