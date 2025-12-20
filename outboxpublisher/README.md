# Outbox Publisher

Scheduled service that reads unpublished events from the outbox table and publishes them to SQS.

## Key Features
- Polls outbox table every 10-30 seconds
- Uses `FOR UPDATE SKIP LOCKED` for concurrent safety
- Guarantees at-least-once delivery
- Automatic retry with backoff
