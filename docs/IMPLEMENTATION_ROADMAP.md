# PAGW Implementation Roadmap - Optimized for Minimal Tekton Builds

> **Goal**: Implement DATA_MODEL_GAPS_ANALYSIS recommendations with minimal Tekton pipeline builds

---

## Build Impact Analysis

| Change Type | Services Impacted | Tekton Builds Required |
|-------------|-------------------|------------------------|
| **Schema migrations** | pasorchestrator only | 1 build (orchestrator) |
| **pagwcore updates** | ALL 10 services | 10 builds (all services) |
| **Individual service code** | 1 service | 1 build per service |
| **Lambda changes** | pas_provider_auth | No Tekton (AWS Lambda deploy) |

**Strategy**: Minimize pagwcore updates by batching all shared code changes into ONE release.

---

## Tekton Build Rounds (3 Total)

### ✅ Round 0: Schema Only (COMPLETED)
**Status**: Done - V003 and V004 migrations committed
**Build**: pasorchestrator only
**Duration**: ~30 minutes

**What was deployed**:
- V003: oauth_provider_registry table
- V004: FK constraints, indexes, views, helper functions

**No code changes** - just schema ready for future use

---

### 🔄 Round 1: pagwcore + Orchestrator (ALL SERVICES REBUILD)
**When**: After all shared library code is complete
**Builds**: pagwcore + ALL 10 microservices
**Duration**: ~2 hours (parallel builds)
**Why all services**: pagwcore dependency means all services must rebuild

#### Tasks for Round 1:

**1.1 Create EventTrackerService in pagwcore** ✅
```java
// pagwcore/src/main/java/com/anthem/pagw/core/service/EventTrackerService.java
- logStageStart(pagwId, stage, eventType)
- logStageComplete(pagwId, stage, eventType, status, durationMs)
- logStageError(pagwId, stage, eventType, errorCode, errorMsg)
```

**1.2 Update RequestTrackerService in pagwcore** ✅
```java
// Add new methods:
- updateIdentifiers(pagwId, payerId, providerNpi, patientMemberId, clientId)
- setCorrelationId(pagwId, correlationId)
- setExpiresAt(pagwId, expiresAt)
- updateProviderContext(pagwId, clientId, providerName, providerNpi, tenant)
- setTimestamps(pagwId, syncProcessedAt, asyncQueuedAt)
```

**1.3 Update OrchestratorService** ✅
```java
// Extract provider context from Lambda headers:
- X-Provider-Id → request_tracker.client_id
- X-Provider-Name → request_tracker.provider_name (if we add this column)
- X-Provider-Npi → request_tracker.provider_npi
- X-Tenant → request_tracker.tenant
- X-Correlation-Id → request_tracker.correlation_id

// Set expires_at
tracker.setExpiresAt(receivedAt.plus(90, ChronoUnit.DAYS));

// Add event tracking
eventTrackerService.logStageStart(pagwId, "ORCHESTRATOR", syncProcessing ? "SYNC_SUBMIT" : "ASYNC_SUBMIT");
```

**1.4 Add Event Tracking to ALL Services** ✅
```java
// Pattern for each listener:
@SqsListener(...)
public void handleMessage(String messageBody) {
    long startTime = System.currentTimeMillis();
    String pagwId = null;
    
    try {
        message = parse(messageBody);
        pagwId = message.getPagwId();
        
        eventTrackerService.logStageStart(pagwId, "SERVICE_NAME", "ASYNC_PROCESS");
        
        // ... processing logic ...
        
        long duration = System.currentTimeMillis() - startTime;
        eventTrackerService.logStageComplete(pagwId, "SERVICE_NAME", "ASYNC_PROCESS", "SUCCESS", duration);
    } catch (Exception e) {
        eventTrackerService.logStageError(pagwId, "SERVICE_NAME", "ASYNC_PROCESS", 
                                          e.getClass().getSimpleName(), e.getMessage());
        throw e;
    }
}
```

**Services to update**:
1. pasrequestparser
2. pasbusinessvalidator
3. pasrequestenricher
4. pasattachmenthandler
5. pasrequestconverter
6. pasapiconnector
7. pascallbackhandler (update existing tracking)
8. pasresponsebuilder
9. passubscriptionhandler
10. pasorchestrator (already done in 1.3)

**Build Order for Round 1**:
```bash
# 1. Build and deploy pagwcore
cd pagwcore
mvn clean deploy -DskipTests
# Push to quay: quay-nonprod.elevancehealth.com/pagw/crln-shared-dev-img-pagwcore:v1.1.0

# 2. Trigger Tekton builds for ALL services (can be parallel)
# Update CACHE_BUST and pagwcore version in Tekton configs
# Services pull new pagwcore during build

# 3. Deploy via Helm in order:
helm upgrade pasorchestrator ...        # First (has migrations)
helm upgrade pasrequestparser ...
helm upgrade pasbusinessvalidator ...
helm upgrade pasrequestenricher ...
helm upgrade pasattachmenthandler ...
helm upgrade pasrequestconverter ...
helm upgrade pasapiconnector ...
helm upgrade pasresponsebuilder ...
helm upgrade pascallbackhandler ...
helm upgrade passubscriptionhandler ...
helm upgrade outboxpublisher ...        # Last
```

**Testing After Round 1**:
```bash
# Submit test request
curl -X POST https://apigee-dev/pas/v1/Claim/\$submit \
  -H "Authorization: Bearer $TOKEN" \
  -d @test-bundle.json

# Check event_tracker populated
psql -c "SELECT stage, event_type, status, duration_ms FROM pagw.event_tracker 
         WHERE pagw_id = 'PAGW-...' ORDER BY started_at;"

# Should see ~8-10 events for complete workflow
```

**Deliverables**:
- ✅ EventTrackerService in pagwcore
- ✅ All services log events to event_tracker
- ✅ Orchestrator extracts provider context from headers
- ✅ Timestamps properly set (sync_processed_at, async_queued_at)

---

### 🔄 Round 2: FHIR Extraction (1 SERVICE REBUILD)
**When**: After Round 1 is stable in DEV
**Builds**: pasrequestparser only
**Duration**: ~15 minutes
**Why only parser**: FHIR extraction logic isolated to parser service

#### Tasks for Round 2:

**2.1 Update RequestParserService** ✅
```java
// Add extraction methods:
private String extractPayerId(Bundle bundle) {
    // From Coverage.payor.reference or Coverage.payor.identifier
    return bundle.getEntry().stream()
        .filter(e -> e.getResource() instanceof Coverage)
        .map(e -> (Coverage) e.getResource())
        .flatMap(c -> c.getPayor().stream())
        .map(p -> p.getIdentifier().getValue())
        .findFirst()
        .orElse(null);
}

private String extractProviderNpi(Bundle bundle) {
    // From Practitioner.identifier where type = NPI
    return bundle.getEntry().stream()
        .filter(e -> e.getResource() instanceof Practitioner)
        .map(e -> (Practitioner) e.getResource())
        .flatMap(p -> p.getIdentifier().stream())
        .filter(id -> "NPI".equals(id.getType().getCodingFirstRep().getCode()))
        .map(id -> id.getValue())
        .findFirst()
        .orElse(null);
}

private String extractPatientMemberId(Bundle bundle) {
    // From Patient.identifier where type = MB (member)
    return bundle.getEntry().stream()
        .filter(e -> e.getResource() instanceof Patient)
        .map(e -> (Patient) e.getResource())
        .flatMap(p -> p.getIdentifier().stream())
        .filter(id -> "MB".equals(id.getType().getCodingFirstRep().getCode()))
        .map(id -> id.getValue())
        .findFirst()
        .orElse(null);
}

// In parse() method:
String payerId = extractPayerId(bundle);
String providerNpi = extractProviderNpi(bundle);
String patientMemberId = extractPatientMemberId(bundle);

requestTrackerService.updateIdentifiers(
    message.getPagwId(),
    payerId,
    providerNpi,
    patientMemberId,
    null  // client_id already set by orchestrator
);
```

**Build for Round 2**:
```bash
# Only rebuild pasrequestparser
cd pagw-microservices/pasrequestparser
# Trigger Tekton build
# Deploy via Helm
helm upgrade pasrequestparser pagwk8s/pasrequestparser \
  -f tekton-values-dev.yaml \
  --namespace pagw-srv-dev
```

**Testing After Round 2**:
```bash
# Submit test request
# Check fields populated
psql -c "SELECT pagw_id, payer_id, provider_npi, patient_member_id, client_id 
         FROM pagw.request_tracker 
         WHERE received_at > NOW() - INTERVAL '1 hour';"

# Verify population rate
psql -c "SELECT 
    COUNT(*) as total,
    COUNT(payer_id) as has_payer,
    COUNT(provider_npi) as has_npi,
    COUNT(patient_member_id) as has_patient,
    COUNT(client_id) as has_client
FROM pagw.request_tracker 
WHERE received_at > NOW() - INTERVAL '1 day';"
```

**Deliverables**:
- ✅ FHIR extraction logic in parser
- ✅ payer_id, provider_npi, patient_member_id populated
- ✅ 100% population rate for new requests

---

### 🔄 Round 3: Lambda Integration (NO TEKTON - AWS LAMBDA DEPLOY)
**When**: After Round 1 is stable
**Builds**: None (Lambda update only)
**Duration**: ~10 minutes
**Why no Tekton**: Lambda deployed via AWS CLI/Console

#### Tasks for Round 3:

**3.1 Update Lambda Authorizer** ✅
```python
# pas_provider_auth/source/handler.py

def lambda_handler(event, context):
    """API Gateway Lambda Authorizer"""
    try:
        # Extract token
        token = extract_token(event)
        
        # Introspect with TotalView
        introspection = introspect_token(token)
        if not introspection.get('active'):
            return generate_deny_policy()
        
        client_id = introspection.get('clientId')
        
        # Lookup provider registration
        provider = lookup_oauth_provider(client_id)
        if not provider or provider['status'] != 'ACTIVE':
            return generate_deny_policy()
        
        # Check entitlements
        if 'PAS_SUBMIT' not in provider['allowed_apis']:
            return generate_deny_policy()
        
        # Extract provider context
        provider_npi = provider['npis'][0] if provider.get('npis') else None
        
        # Generate policy with context
        return {
            'principalId': client_id,
            'policyDocument': generate_allow_policy(event['methodArn']),
            'context': {
                # IMPORTANT: These become headers in API Gateway
                'providerId': client_id,              # → X-Provider-Id
                'providerNpi': provider_npi or '',    # → X-Provider-Npi
                'tenant': provider['tenant'],          # → X-Tenant
                'entityName': provider['entity_name'], # → X-Entity-Name
                'allowedApis': ','.join(provider['allowed_apis'])  # → X-Allowed-Apis
            }
        }
    except Exception as e:
        logger.error(f"Authorization failed: {str(e)}")
        return generate_deny_policy()
```

**3.2 Configure API Gateway Mapping** ✅
```yaml
# API Gateway stage settings
# Map Lambda context to request headers:
Context Mappings:
  - context.providerId → $context.authorizer.providerId
  - context.providerNpi → $context.authorizer.providerNpi
  - context.tenant → $context.authorizer.tenant
  - context.entityName → $context.authorizer.entityName

Request Header Mappings:
  - X-Provider-Id: $context.authorizer.providerId
  - X-Provider-Npi: $context.authorizer.providerNpi
  - X-Tenant: $context.authorizer.tenant
  - X-Entity-Name: $context.authorizer.entityName
  - X-Correlation-Id: $input.params('X-Correlation-Id')  # Pass through
```

**Deploy Lambda**:
```bash
# Package Lambda
cd pagw-platform-lambdas/pas_provider_auth
pip install -r requirements.txt -t package/
cd package && zip -r ../lambda.zip . && cd ..
zip -g lambda.zip source/*.py

# Deploy to AWS
aws lambda update-function-code \
  --function-name pas-provider-auth-dev \
  --zip-file fileb://lambda.zip \
  --region us-east-2

# Update environment variables
aws lambda update-function-configuration \
  --function-name pas-provider-auth-dev \
  --environment Variables="{
    TOTALVIEW_INTROSPECTION_URL=https://dev.totalview.healthos.carelon.com/introspection/api/v1/token/details,
    DB_SECRET_ARN=arn:aws:secretsmanager:us-east-2:xxx:secret:pagw/dev/db,
    ENVIRONMENT=dev
  }"
```

**Testing After Round 3**:
```bash
# Get token from TotalView
TOKEN=$(curl -X POST https://dev.totalview.healthos.carelon.com/oauth/token \
  -d "grant_type=client_credentials" \
  -d "client_id=bdda3ee5..." \
  -d "client_secret=..." | jq -r '.access_token')

# Submit request via APIGEE
curl -X POST https://apigee-dev.elevancehealth.com/pas/v1/Claim/\$submit \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Correlation-Id: test-$(uuidgen)" \
  -d @test-bundle.json

# Verify client_id populated
psql -c "SELECT pagw_id, client_id, tenant, provider_npi 
         FROM pagw.request_tracker 
         WHERE received_at > NOW() - INTERVAL '5 minutes';"

# Should show:
# client_id = 'bdda3ee5-df8f-4c86-8d89-4a160e90764d'
# tenant = 'carelon'
# provider_npi = '1234567890'
```

**Deliverables**:
- ✅ Lambda passes provider context via headers
- ✅ Orchestrator extracts and stores client_id
- ✅ Complete provider tracking (client_id → oauth_provider_registry)

---

## Summary: 3 Build Rounds

| Round | Services Built | Duration | Parallel? | When |
|-------|---------------|----------|-----------|------|
| 0. Schema ✅ | pasorchestrator | 30 min | N/A | **DONE** |
| 1. Core + Events | ALL 10 services | 2 hours | Yes | Week 1-2 |
| 2. FHIR Extract | pasrequestparser | 15 min | N/A | Week 2 |
| 3. Lambda Auth | pas_provider_auth | 10 min | N/A | Week 2-3 |

**Total Tekton Builds**: 
- Round 0: 1 build (orchestrator) ✅
- Round 1: 11 builds (pagwcore + 10 services)
- Round 2: 1 build (parser)
- **Total: 13 builds across 3 rounds**

**Why this sequence?**
1. ✅ Schema first (no code impact, safe to deploy)
2. **pagwcore changes batched** (unavoidable all-services rebuild, do once)
3. **Parser isolated** (FHIR extraction doesn't affect pagwcore)
4. **Lambda separate** (no Tekton needed)

---

## Validation Queries

### After Round 1 (Event Tracking):
```sql
-- Check event_tracker population
SELECT 
    stage,
    event_type,
    COUNT(*) as event_count,
    AVG(duration_ms) as avg_duration_ms,
    COUNT(*) FILTER (WHERE status = 'SUCCESS') as successes,
    COUNT(*) FILTER (WHERE status = 'FAILED') as failures
FROM pagw.event_tracker
WHERE created_at > NOW() - INTERVAL '1 day'
GROUP BY stage, event_type
ORDER BY stage;

-- Should see 8+ stages with events
```

### After Round 2 (FHIR Extraction):
```sql
-- Check field population rate
SELECT 
    'payer_id' as field,
    COUNT(*) as total,
    COUNT(payer_id) as populated,
    ROUND(100.0 * COUNT(payer_id) / COUNT(*), 2) as pct
FROM pagw.request_tracker
WHERE received_at > NOW() - INTERVAL '1 day'
UNION ALL
SELECT 
    'provider_npi',
    COUNT(*),
    COUNT(provider_npi),
    ROUND(100.0 * COUNT(provider_npi) / COUNT(*), 2)
FROM pagw.request_tracker
WHERE received_at > NOW() - INTERVAL '1 day'
UNION ALL
SELECT 
    'patient_member_id',
    COUNT(*),
    COUNT(patient_member_id),
    ROUND(100.0 * COUNT(patient_member_id) / COUNT(*), 2)
FROM pagw.request_tracker
WHERE received_at > NOW() - INTERVAL '1 day';

-- Should be 90%+ for all fields
```

### After Round 3 (Lambda Integration):
```sql
-- Check OAuth provider tracking
SELECT 
    opr.entity_name,
    opr.tenant,
    COUNT(*) as request_count,
    MAX(rt.received_at) as last_request
FROM pagw.request_tracker rt
JOIN pagw.oauth_provider_registry opr ON opr.client_id = rt.client_id
WHERE rt.received_at > NOW() - INTERVAL '7 days'
GROUP BY opr.entity_name, opr.tenant
ORDER BY request_count DESC;

-- Should see provider names, not just client IDs
```

### Complete Request Story:
```sql
-- Use helper function from V004
SELECT pagw.get_request_story('PAGW-20251225-00001-...');

-- Returns JSON with:
-- - request details
-- - all event_tracker entries
-- - attachments
-- - audit trail
```

---

## Rollback Plan

### If Round 1 Fails:
```bash
# Rollback pagwcore to previous version
# Rebuild all services with old pagwcore
# Schema remains (V003, V004 are backward compatible)
```

### If Round 2 Fails:
```bash
# Just rollback pasrequestparser
# Other services unaffected
# Fields remain NULL (acceptable temporarily)
```

### If Round 3 Fails:
```bash
# Rollback Lambda to previous version
# Orchestrator still works (client_id remains NULL)
# Provider tracking disabled until fixed
```

---

## Success Metrics

### After Full Implementation:

| Metric | Target | Query |
|--------|--------|-------|
| Event tracking coverage | 100% | All stages have events |
| Field population rate | >95% | payer_id, provider_npi, patient_member_id |
| Provider identification | 100% | All requests linked to oauth_provider_registry |
| Performance impact | <5% | Compare avg duration before/after |
| Event_tracker size | <10MB/day | ~10 events × 1KB × 1000 requests/day |

### Monitoring Alerts:

```sql
-- Alert if event tracking drops
SELECT COUNT(*) FROM pagw.request_tracker rt
LEFT JOIN pagw.event_tracker et ON et.pagw_id = rt.pagw_id
WHERE rt.received_at > NOW() - INTERVAL '1 hour'
  AND et.id IS NULL;
-- Alert if > 10

-- Alert if field population drops
SELECT COUNT(*) FROM pagw.request_tracker
WHERE received_at > NOW() - INTERVAL '1 hour'
  AND (payer_id IS NULL OR provider_npi IS NULL);
-- Alert if > 5% of total
```

---

## Related Documentation

- [DATA_MODEL_GAPS_ANALYSIS.md](DATA_MODEL_GAPS_ANALYSIS.md) - Original gap analysis
- [PROVIDER_AUTH_INTEGRATION.md](PROVIDER_AUTH_INTEGRATION.md) - Lambda authorizer design
- [OPERATIONAL_DATA_MODEL.md](OPERATIONAL_DATA_MODEL.md) - Complete schema reference
- [provider-auth-registration.md](provider-auth-registration.md) - OAuth provider registration flow

---

**Last Updated**: December 25, 2025  
**Status**: Round 0 Complete ✅ | Round 1 Pending | Round 2 Pending | Round 3 Pending
