# PAGW Operational Data Model v2

This package contains:
- `ddl_v2.sql` – Aurora PostgreSQL 15.x compatible DDL for PAGW operational store
- `sqs_event_schemas.json` – canonical SQS envelope + event types + logical destinations
- `flyway_plan.md` – recommended Flyway migration sequence (baseline + evolutions)

## How to apply locally

If you have a local postgres container:

```bash
psql "$DATABASE_URL" -f ddl_v2.sql
```

## How to use the SQS schema

All PAGW services should send/receive messages using the `PagwEventEnvelope.v1`
format. The physical queue URL is resolved by config; messages carry **logical**
`replyTo` and `stage` identifiers.

## Notes

- Store full FHIR bundles in S3; keep DB rows lean (pointers + indexes).
- Partition `event_tracker` and `audit_log` once volumes grow.
