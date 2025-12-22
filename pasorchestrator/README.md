# PAGW PAS Orchestrator

Entry point service for Prior Authorization requests.

## Endpoints

- `POST /api/v1/pas/Claim/$submit` - Submit PA request
- `GET /api/v1/pas/status/{pagwId}` - Get status
- `GET /actuator/health` - Health check

## Build

```bash
cd source
mvn clean package
```

## Run

```bash
mvn spring-boot:run
```
