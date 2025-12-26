# API Endpoint Migration: /pas/v1 → /pas/api/v1

**Date:** December 25, 2025  
**Status:** ✅ COMPLETED

## Overview

Migrated all PAS API endpoints from `/pas/v1/*` pattern to `/pas/api/v1/*` pattern to standardize API versioning and improve API management capabilities.

## Changes Summary

### Pattern Change
```
OLD: /pas/v1/{operation}
NEW: /pas/api/v1/{operation}
```

### Affected Endpoints

| Endpoint | Old Path | New Path |
|----------|----------|----------|
| Submit | `/pas/v1/submit` | `/pas/api/v1/submit` |
| Status | `/pas/v1/status/{id}` | `/pas/api/v1/status/{id}` |
| Inquiry | `/pas/v1/inquiry` | `/pas/api/v1/inquiry` |
| Update | `/pas/v1/update` | `/pas/api/v1/update` |
| Cancel | `/pas/v1/cancel` | `/pas/api/v1/cancel` |
| Parse (internal) | `/pas/v1/parse` | `/pas/api/v1/parse` |
| Validate (internal) | `/pas/v1/validate` | `/pas/api/v1/validate` |
| Health | `/pas/v1/health` | `/pas/api/v1/health` |
| Subscriptions | `/pas/v1/subscriptions` | `/pas/api/v1/subscriptions` |

## Code Changes

### pagw-microservices (Commit: fdea641)

**Controllers Updated:**
1. **OrchestratorController.java**
   - Changed `@RequestMapping("/pas/v1")` → `@RequestMapping("/pas/api/v1")`
   - Handles: submit, inquiry, update, cancel, status, subscriptions, health

2. **ParserController.java**
   - Changed `@RequestMapping("/pas/v1")` → `@RequestMapping("/pas/api/v1")`
   - Handles: parse (internal)

3. **ValidatorController.java**
   - Changed `@RequestMapping("/pas/v1")` → `@RequestMapping("/pas/api/v1")`
   - Handles: validate (internal)

**Services Updated:**
1. **SyncProcessingService.java**
   - Line 300: `requestParserUrl + "/pas/api/v1/parse"`
   - Line 365: `businessValidatorUrl + "/pas/api/v1/validate"`

2. **OrchestratorService.java**
   - Line 436: Status message updated to reference `/pas/api/v1/status/{pagwId}`

**Documentation Updated:**
- IMPLEMENTATION_ROADMAP.md
- OPERATIONAL_DATA_MODEL.md
- PAGW-Demo-Guide.md
- PROVIDER_AUTH_INTEGRATION.md
- da-vinci-pas-compliance.md
- provider-auth-registration.md
- curl-demo.txt
- prov-auth.txt

**Test Files Updated:**
- test/postman/PAGW-PAS-Collection.postman_collection.json
- test/postman/README.md
- test/mocks/mock-payer-expectations.json

### pagwcore (Commit: e3ef4e9)

**PayerConfigurationService.java:**
- Line 48: Carelon endpoint → `https://api.carelon.com/pas/api/v1`
- Line 203: Default payer → `https://api.default-payer.com/pas/api/v1`

### pagw-ui (Commit: 1de7084)

**pagwApi.js:**
- Submit: `/pas/api/v1/submit`
- Status: `/pas/api/v1/status/${pagwId}`
- Inquiry: `/pas/api/v1/inquiry`

**Documentation:**
- README.md
- QUICK_START.md
- MAC_SETUP.md

### pagw-platform-lambdas (Commit: 23f4680)

**pas_mapper/source/handler.py:**
- Line 105: Aetna endpoint → `https://api.aetna.com/pas/api/v1`

### pagw-demos (Commit: 004833a)

**Updated:**
- pas/QUICK_REFERENCE.md
- pas/issues/output.txt

## Deployment Impact

### ⚠️ BREAKING CHANGE
This is a **BREAKING CHANGE** for all API consumers. Clients must update their endpoint URLs.

### Deployment Strategy

**Option 1: Hard Cutover (Recommended for controlled environments)**
```bash
# Deploy all services simultaneously
kubectl set image deployment/pasorchestrator pasorchestrator=...
kubectl set image deployment/pasrequestparser pasrequestparser=...
kubectl set image deployment/pasbusinessvalidator pasbusinessvalidator=...

# Update API Gateway/Ingress
# Update Apigee configuration
```

**Option 2: Dual Path Support (For production)**
If gradual migration needed, configure ingress to accept both paths temporarily:
```yaml
# Ingress rewrites (if needed)
nginx.ingress.kubernetes.io/rewrite-target: /pas/api/v1/$1
```

### Client Updates Required

**1. External Clients (Providers)**
- Update API base URL configuration
- Test connectivity with new endpoints
- Coordinate cutover timing

**2. Internal Clients**
- ✅ pagw-ui: Already updated (commit 1de7084)
- ✅ Lambda functions: Already updated (commit 23f4680)
- 🔍 Check: Any hardcoded URLs in scripts/tools

**3. API Gateway**
- Update Apigee proxy configuration
- Update path routing rules
- Update documentation/developer portal

**4. Monitoring & Observability**
- Update CloudWatch dashboards (path filters)
- Update Datadog monitors (endpoint patterns)
- Update log queries/alerts

## Testing Checklist

### Pre-Deployment
- ✅ All microservices compile successfully
- ✅ pagwcore installed to Maven repository
- ✅ Unit tests pass (if applicable)

### Post-Deployment
- [ ] Health checks: `GET /pas/api/v1/health`
- [ ] Submit sync: `POST /pas/api/v1/submit?syncMode=true`
- [ ] Submit async: `POST /pas/api/v1/submit?syncMode=false`
- [ ] Status query: `GET /pas/api/v1/status/{pagwId}`
- [ ] Inquiry: `POST /pas/api/v1/inquiry`
- [ ] Internal parser: `POST /pas/api/v1/parse`
- [ ] Internal validator: `POST /pas/api/v1/validate`

### Sample Test Command
```bash
# Test new endpoint
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/pas/api/v1/submit?syncMode=true" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: test-endpoint-migration" \
  -H "X-Tenant-ID: ANTHEM" \
  -d @sample-bundle.json

# Verify old endpoint returns 404 (after ingress update)
curl -X POST "https://pasorchestrator.pagwdev.awsdns.internal.das/pas/v1/submit?syncMode=true" \
  -H "Content-Type: application/json" \
  -d @sample-bundle.json
# Expected: 404 Not Found
```

## Rollback Plan

### Code Rollback
```bash
# Rollback to previous commits
cd pagw-microservices
git revert fdea641
git push origin master

cd ../pagwcore
git revert e3ef4e9
git push origin main

cd ../pagw-ui
git revert 1de7084
git push origin master

cd ../pagw-platform-lambdas
git revert 23f4680
git push origin master

# Rebuild and redeploy
```

### Configuration Rollback
- Revert API Gateway/Apigee configuration
- Revert ingress rules (if modified)

## Communication Plan

### Internal Communication
**To:** Engineering team, DevOps, QA  
**Subject:** API Endpoint Pattern Change - `/pas/v1` → `/pas/api/v1`  
**Message:**
> All PAS API endpoints have been updated from `/pas/v1/*` to `/pas/api/v1/*`. This change standardizes our API versioning approach. Services will be deployed to DEV on [DATE]. Please update any internal tools, scripts, or documentation that reference the old endpoints.

### External Communication (If applicable)
**To:** Provider integration partners  
**Subject:** Important: PAS API Endpoint Update Required  
**Message:**
> We are updating our PAS API endpoint pattern for improved API management and versioning. Effective [DATE], all API requests must use the new `/pas/api/v1/*` path pattern. The previous `/pas/v1/*` paths will be deprecated. Please update your integrations accordingly.
>
> New endpoint pattern: `https://[base-url]/pas/api/v1/submit`  
> Previous pattern: `https://[base-url]/pas/v1/submit`
>
> Full documentation: [LINK]

## Benefits

1. **API Management**: Clearer separation between API operations and versions
2. **Consistency**: Aligns with industry-standard API path patterns
3. **Gateway Integration**: Better compatibility with API gateway routing rules
4. **Future-Proofing**: Easier to add API v2, v3 alongside v1
5. **Discoverability**: `/api` prefix makes API endpoints more discoverable

## References

- **PAS Microservices PR:** fdea641
- **Core Library PR:** e3ef4e9
- **UI PR:** 1de7084
- **Lambda Functions PR:** 23f4680
- **Da Vinci PAS IG:** http://hl7.org/fhir/us/davinci-pas/
- **FHIR R4 Spec:** http://hl7.org/fhir/R4/

## Related Documentation

- [IMPLEMENTATION_ROADMAP.md](./IMPLEMENTATION_ROADMAP.md)
- [OPERATIONAL_DATA_MODEL.md](./OPERATIONAL_DATA_MODEL.md)
- [PAGW-Demo-Guide.md](./PAGW-Demo-Guide.md)
- [da-vinci-pas-compliance.md](./da-vinci-pas-compliance.md)

---

**Next Steps:**
1. Deploy to DEV environment
2. Run integration tests
3. Update API Gateway configuration
4. Notify provider partners (if applicable)
5. Deploy to preprod/prod
6. Monitor for 404 errors on old paths
