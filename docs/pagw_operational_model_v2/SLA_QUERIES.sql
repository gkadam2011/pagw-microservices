-- =====================================================================
-- PAGW SLA / Ops Queries (examples)
-- =====================================================================

-- 1) Current in-flight requests by status/stage
SELECT tenant, status, last_stage, count(*) AS cnt
FROM request_tracker
WHERE completed_at IS NULL
GROUP BY tenant, status, last_stage
ORDER BY cnt DESC;

-- 2) P95 end-to-end latency (received_at -> completed_at) over last 24h
SELECT tenant,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (completed_at - received_at)) * 1000) AS p95_ms
FROM request_tracker
WHERE completed_at IS NOT NULL
  AND received_at >= now() - interval '24 hours'
GROUP BY tenant;

-- 3) Stage latency (event start -> complete) p95 by stage (last 24h)
SELECT tenant, stage,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_duration_ms
FROM event_tracker
WHERE created_at >= now() - interval '24 hours'
  AND duration_ms IS NOT NULL
GROUP BY tenant, stage
ORDER BY tenant, stage;

-- 4) Failure rate by event_type (last 24h)
SELECT tenant, event_type,
       count(*) FILTER (WHERE status IN ('FAIL','ERROR'))::float / NULLIF(count(*),0) AS fail_rate,
       count(*) AS total
FROM event_tracker
WHERE created_at >= now() - interval '24 hours'
GROUP BY tenant, event_type
ORDER BY fail_rate DESC NULLS LAST;

-- 5) Top error codes (last 7d)
SELECT tenant, error_code, count(*) AS cnt
FROM event_tracker
WHERE created_at >= now() - interval '7 days'
  AND error_code IS NOT NULL
GROUP BY tenant, error_code
ORDER BY cnt DESC
LIMIT 50;
