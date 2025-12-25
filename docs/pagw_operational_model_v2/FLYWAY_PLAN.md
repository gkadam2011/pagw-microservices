# Flyway Migration Plan (recommended)

Create a dedicated `pagw-core-db` repo or keep DDL under an `infra-db/` folder.

## Suggested structure

```
db/
  flyway.conf
  sql/
    V1__init_operational_model.sql
    V2__add_workflow_versioning_and_event_ordering.sql
    V3__partition_event_and_audit_tables.sql
    V4__indexes_for_sla_queries.sql
```

## Rules

- **Never edit** applied migrations.
- Add new `V#__...sql` files.
- Use `R__` repeatable scripts only for views/functions (optional).

## Local run (example)

```bash
flyway -url=jdbc:postgresql://localhost:5432/pagw -user=postgres -password=... migrate
flyway info
```

## Production run

Run Flyway in CI/CD or as a Kubernetes Job with IRSA for secret retrieval.
