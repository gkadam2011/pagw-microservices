# PAS Provider Auth - Integration & Testing Plan

## Overview

Provider authentication and authorization flow for PAGW via TotalView (ElevanceHealth's provider registration system).

### Responsibilities

**NOT PAGW's Responsibility:**
- Provider registration
- API key/token generation
- Token creation

**PAGW's Responsibility:**
- **Token introspection** via TotalView API
- Provider authorization validation
- Routing logic based on provider type/tenant

---

## Current Architecture

```
Provider Registration (External - TotalView):
    Provider → TotalView → Register → Get API Key/Token

Request Flow (PAGW):
    Provider → APIGEE → API Gateway → Lambda Authorizer (Token Introspection) → Orchestrator
                                              ↓
                                      TotalView API
                                    (Token Introspection)
```

### TotalView Token Introspection

**Endpoint**: `https://dev.totalview.healthos.carelon.com/introspection/api/v1/token/details`

**Request**:
```http
POST /introspection/api/v1/token/details HTTP/1.1
Host: dev.totalview.healthos.carelon.com
Authorization: Bearer {SERVICE_TOKEN}
Content-Type: application/x-www-form-urlencoded

accessToken={PROVIDER_TOKEN}
```

**Response** (Active Token):
```json
{
  "active": true,
  "client_id": "bdda3ee5-df8f-4c86-8d89-4a160e90764d",
  "entity_name": "b2b_test",
  "entity_type": "B2B",
  "scope": "patient/*.read openid profile",
  "exp": 1763381261,
  "iat": 1763377661,
  "iss": "https://dev.totalview.healthos.carelon.com",
  "sub": "TOTALVIEWMIDDLEWARE/HEALTHOS"
}
```

**Response** (Expired/Invalid Token):
```json
{
  "active": false
}
```

---

## Provider Routing Logic

### Decision Matrix: Request Converter vs ACMP Mapping

**Simple Rule**: Routing is based on **tenant** field from token introspection

| Tenant | System | Action Required |
|--------|--------|-----------------|
| **elevance** | ACMP payer system | ✅ Call ACMP Mapping API |
| **carelon** | Carelon endpoints | ✅ Use Request Converter |

### Routing Rules

```python
def determine_routing_strategy(provider_context):
    """
    Determine if provider needs request converter or ACMP mapping.
    
    Simple tenant-based routing:
    - tenant=elevance → ACMP mapping API
    - tenant=carelon → Request Converter
    
    Returns:
        {
            "requires_converter": bool,
            "requires_acmp_mapping": bool,
            "payer_system": str,  # "carelon", "acmp"
            "mapping_source": str  # "converter", "acmp_api"
        }
    """
    
    # ElevanceHealth ACMP payer system - call ACMP API
    if provider_context.tenant == 'elevance':
        return {
            "requires_converter": False,
            "requires_acmp_mapping": True,
            "payer_system": "acmp",
            "mapping_source": "acmp_api"
        }
    
    # Carelon - use converter (default for all other tenants)
    return {
        "requires_converter": True,
        "requires_acmp_mapping": False,
        "payer_system": "carelon",
        "mapping_source": "converter"
    }
```

### ACMP Mapping API

**Purpose**: Get provider-to-payer mappings for ElevanceHealth ACMP system

**Endpoint**: TBD - Need ACMP API documentation
```
GET /acmp/api/v1/provider/mappings?npi={NPI}&payer_id={PAYER_ID}
```

**Expected Response**:
```json
{
  "provider_npi": "1234567890",
  "payer_id": "ELEVANCE",
  "mappings": {
    "service_type_codes": {
      "fhir_code": "payer_code",
      "diagnostic": "DIAG01"
    },
    "procedure_codes": {
      "99213": "EVAL-MID"
    }
  }
}
```

---

## Lambda Authorizer Updates

### Current Implementation Gap

The Lambda currently:
- ✅ Validates JWT structure
- ✅ Checks provider_registry table
- ❌ **Does NOT call TotalView introspection API**

### Required Changes

#### 1. Add Token Introspection

**File**: `pas_provider_auth/source/utils.py`

```python
class TokenValidator:
    
    def __init__(self):
        self.environment = os.environ.get('ENVIRONMENT', 'dev')
        self.totalview_introspection_url = self._get_introspection_url()
        self.service_token = self._get_service_token()
    
    def _get_introspection_url(self):
        """Get TotalView introspection URL based on environment."""
        urls = {
            'dev': 'https://dev.totalview.healthos.carelon.com/introspection/api/v1/token/details',
            'sit': 'https://sit.totalview.healthos.carelon.com/introspection/api/v1/token/details',
            'preprod': 'https://preprod.totalview.healthos.carelon.com/introspection/api/v1/token/details',
            'prod': 'https://totalview.healthos.carelon.com/introspection/api/v1/token/details'
        }
        return urls.get(self.environment, urls['dev'])
    
    def _get_service_token(self):
        """Get PAGW service token for TotalView introspection from Secrets Manager."""
        secret_name = f"pagw/{self.environment}/totalview-service-token"
        try:
            response = secrets_client.get_secret_value(SecretId=secret_name)
            secret_value = json.loads(response['SecretString'])
            return secret_value.get('token', '')
        except Exception as e:
            logger.error(f"Failed to get service token: {str(e)}")
            raise
    
    def validate_token(self, token: str) -> Optional[ProviderContext]:
        """
        Validate token via TotalView introspection API.
        
        Args:
            token: Provider's access token
        
        Returns:
            ProviderContext if valid, None otherwise
        """
        if not token:
            return None
        
        # Call TotalView introspection API
        introspection_result = self._introspect_token(token)
        if not introspection_result:
            logger.warning("Token introspection failed")
            return None
        
        # Check if token is active
        if not introspection_result.get('active', False):
            logger.warning("Token is not active")
            return None
        
        # Extract provider context from introspection response
        return self._extract_provider_context(introspection_result)
    
    def _introspect_token(self, access_token: str) -> Optional[Dict]:
        """
        Call TotalView token introspection API.
        
        Args:
            access_token: Provider's access token to introspect
        
        Returns:
            Introspection response dict or None on failure
        """
        import requests
        
        try:
            headers = {
                'Authorization': f'Bearer {self.service_token}',
                'Content-Type': 'application/x-www-form-urlencoded'
            }
            
            data = {
                'accessToken': access_token
            }
            
            response = requests.post(
                self.totalview_introspection_url,
                headers=headers,
                data=data,
                timeout=10
            )
            
            response.raise_for_status()
            return response.json()
            
        except requests.exceptions.RequestException as e:
            logger.error(f"Token introspection API error: {str(e)}")
            return None
    
    def _extract_provider_context(self, introspection_data: Dict) -> ProviderContext:
        """
        Extract provider context from TotalView introspection response.
        
        Args:
            introspection_data: Response from introspection API
        
        Returns:
            ProviderContext with provider details
        """
        client_id = introspection_data.get('client_id', '')
        entity_name = introspection_data.get('entity_name', '')
        entity_type = introspection_data.get('entity_type', 'B2B')
        
        # Determine tenant from issuer URL
        issuer = introspection_data.get('iss', '')
        tenant = self._determine_tenant(issuer, entity_type)
        
        # Extract expiry
        exp_timestamp = introspection_data.get('exp')
        token_expiry = None
        if exp_timestamp:
            token_expiry = datetime.fromtimestamp(exp_timestamp, tz=timezone.utc)
        
        return ProviderContext(
            client_id=client_id,
            tenant=tenant,
            provider_name=entity_name,
            provider_type=entity_type,
            issuer=issuer,
            subject=introspection_data.get('sub', ''),
            token_expiry=token_expiry,
            claims=introspection_data,
            environment=self.environment
        )
    
    def _determine_tenant(self, issuer: str, entity_type: str) -> str:
        """Determine tenant from issuer URL and entity type."""
        if 'carelon' in issuer.lower():
            return 'carelon'
        elif 'elevance' in issuer.lower():
            # Need logic to differentiate elevance vs elevance-subsidiary
            # For now, default to elevance (ACMP)
            return 'elevance'
        elif 'bcbsa' in issuer.lower():
            return 'bcbsa'
        else:
            return 'unknown'
```

#### 2. Add Routing Context to Policy

**File**: `pas_provider_auth/source/handler.py`

```python
def _allow_policy(self, method_arn: str, context: ProviderContext) -> Dict[str, Any]:
    """
    Generate Allow policy with provider and routing context.
    """
    # Determine routing strategy
    routing = self._determine_routing_strategy(context)
    
    # Build context to pass to orchestrator
    policy_context = {
        'providerId': context.client_id,
        'providerName': context.provider_name,
        'providerType': context.provider_type,
        'tenant': context.tenant,
        'npi': context.npi or '',
        'authMethod': 'JWT',
        'environment': context.environment,
        # Routing information
        'requiresConverter': str(routing['requires_converter']).lower(),
        'requiresAcmpMapping': str(routing['requires_acmp_mapping']).lower(),
        'payerSystem': routing['payer_system'],
        'mappingSource': routing['mapping_source']
    }
    
    policy = AuthPolicy(context.client_id, method_arn)
    policy.allow_all_methods()
    
    return {
        'principalId': context.client_id,
        'policyDocument': policy.build(),
        'context': policy_context
    }

def _determine_routing_strategy(self, context: ProviderContext) -> Dict:
    """Determine routing strategy based on provider tenant."""
    # Implementation from routing logic above
    # ...
```

---

## Orchestrator Integration

### Extract Provider Context from API Gateway

**File**: `pasorchestrator/source/src/main/java/com/anthem/pagw/orchestrator/controller/OrchestratorController.java`

```java
@PostMapping("/pas/v1/submit")
public ResponseEntity<ClaimResponse> submit(
        @RequestBody String requestBody,
        @RequestHeader(value = "X-Provider-Id", required = false) String providerId,
        @RequestHeader(value = "X-Provider-Name", required = false) String providerName,
        @RequestHeader(value = "X-Provider-Type", required = false) String providerType,
        @RequestHeader(value = "X-Tenant", required = false) String tenant,
        @RequestHeader(value = "X-Provider-Npi", required = false) String npi,
        @RequestHeader(value = "X-Requires-Converter", required = false) String requiresConverter,
        @RequestHeader(value = "X-Requires-Acmp-Mapping", required = false) String requiresAcmpMapping,
        @RequestHeader(value = "X-Payer-System", required = false) String payerSystem,
        @RequestHeader(value = "X-Mapping-Source", required = false) String mappingSource) {
    
    // Build provider context
    ProviderContext providerContext = ProviderContext.builder()
        .providerId(providerId)
        .providerName(providerName)
        .providerType(providerType)
        .tenant(tenant)
        .npi(npi)
        .requiresConverter(Boolean.parseBoolean(requiresConverter))
        .requiresAcmpMapping(Boolean.parseBoolean(requiresAcmpMapping))
        .payerSystem(payerSystem)
        .mappingSource(mappingSource)
        .build();
    
    // Pass to service
    return orchestratorService.processSubmit(requestBody, providerContext);
}
```

### Update Request Tracker with Provider Info

**File**: `pasorchestrator/source/src/main/java/com/anthem/pagw/orchestrator/service/OrchestratorService.java`

```java
private RequestTracker createRequestTracker(PasRequest request, ProviderContext providerContext) {
    return RequestTracker.builder()
        .pagwId(generatePagwId())
        .status("RECEIVED")
        .tenant(providerContext.getTenant())
        .sourceSystem("APIGEE")
        .providerId(providerContext.getProviderId())
        .providerName(providerContext.getProviderName())
        .providerNpi(providerContext.getNpi())
        // ... other fields
        .build();
}
```

### Routing Logic in Orchestrator

```java
private String determineNextStage(ProviderContext providerContext) {
    if (providerContext.isRequiresAcmpMapping()) {
        // For ACMP payers, call mapping API first
        return "ACMP_MAPPER";
    } else if (providerContext.isRequiresConverter()) {
        // For Carelon/Elevance subsidiaries, use converter
        return "REQUEST_CONVERTER";
    } else {
        // Default flow: parser → validator → enricher
        return "REQUEST_PARSER";
    }
}
```

---

## Testing Plan

### Phase 1: Lambda Unit Testing (Local)

**Location**: `pas_provider_auth/source/test_handler.py`

```python
def test_token_introspection_active():
    """Test successful token introspection with active token."""
    # Mock TotalView API response
    # Test Lambda authorizer
    # Verify Allow policy returned

def test_token_introspection_expired():
    """Test token introspection with expired token."""
    # Mock TotalView API response with active=false
    # Verify Deny policy returned

def test_routing_carelon_provider():
    """Test routing logic for Carelon provider (tenant=carelon)."""
    # Verify requires_converter=true, requires_acmp_mapping=false
    # Verify payer_system=carelon

def test_routing_elevance_acmp():
    """Test routing logic for ElevanceHealth ACMP payer (tenant=elevance)."""
    # Verify requires_converter=false, requires_acmp_mapping=true
    # Verify payer_system=acmp
```

### Phase 2: Lambda Integration Testing (DEV)

**Prerequisites**:
1. Lambda deployed to DEV ✅ (already deployed)
2. TotalView service token in Secrets Manager
3. API Gateway configured with Lambda Authorizer
4. Test provider registered in TotalView

**Test Cases**:

#### Test 1: Valid Carelon Provider Token
```bash
# Get test token from TotalView
TOKEN=$(curl -X POST 'https://perf.totalview.healthos.carelon.com/client.oauth2/unregistered/api/v1/token' \
  --header 'Authorization: Basic {CLIENT_CREDENTIALS}' \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' | jq -r '.access_token')

# Call PAGW API with token
curl -X POST 'https://pasorchestrator.pagwdev.awsdns.internal.das/pas/v1/submit' \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '@test-bundle.json'

# Expected: 
# - Lambda allows request
# - Headers include X-Requires-Converter: true
# - Request reaches orchestrator
```

#### Test 2: Expired Token
```bash
# Use expired token
curl -X POST 'https://pasorchestrator.pagwdev.awsdns.internal.das/pas/v1/submit' \
  -H "Authorization: Bearer ${EXPIRED_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '@test-bundle.json'

# Expected: 401 Unauthorized
```

#### Test 3: Invalid Token
```bash
curl -X POST 'https://pasorchestrator.pagwdev.awsdns.internal.das/pas/v1/submit' \
  -H "Authorization: Bearer invalid-token" \
  -H 'Content-Type: application/json' \
  -d '@test-bundle.json'

# Expected: 401 Unauthorized
```

### Phase 3: End-to-End Testing

**Scenario 1**: Carelon Provider → Request Converter Flow
```
Provider Token → Lambda (introspect) → Orchestrator → Request Converter → Parser → Validator → ...
```

**Scenario 2**: ElevanceHealth ACMP → Mapping API Flow
```
Provider Token → Lambda (introspect) → Orchestrator → ACMP Mapper → Parser → Validator → ...
```

---

## Configuration Requirements

### AWS Secrets Manager

#### 1. TotalView Service Token
```json
{
  "name": "pagw/dev/totalview-service-token",
  "value": {
    "token": "eyJhbGciOiJSUzI1NiJ9...",
    "expires_at": "2025-12-31T23:59:59Z"
  }
}
```

#### 2. ACMP API Credentials (for future)
```json
{
  "name": "pagw/dev/acmp-api-credentials",
  "value": {
    "api_key": "acmp-api-key",
    "endpoint": "https://acmp.elevancehealth.com/api/v1"
  }
}
```

### API Gateway Configuration

**Lambda Authorizer Settings**:
- Type: TOKEN
- Token Source: Authorization
- Token Validation: None (handled by Lambda)
- Authorization Caching: Enabled (300 seconds)
- Result TTL: 300 seconds

**Header Mapping** (from Lambda context to backend):
- `providerId` → `X-Provider-Id`
- `providerName` → `X-Provider-Name`
- `tenant` → `X-Tenant`
- `npi` → `X-Provider-Npi`
- `requiresConverter` → `X-Requires-Converter`
- `requiresAcmpMapping` → `X-Requires-Acmp-Mapping`
- `payerSystem` → `X-Payer-System`
- `mappingSource` → `X-Mapping-Source`

---

## TODO List

### High Priority (Week 1)

- [ ] **Lambda Updates**
  - [ ] Add TotalView introspection API call to `TokenValidator.validate_token()`
  - [ ] Add `requests` library to Lambda dependencies
  - [ ] Add routing logic to determine converter vs ACMP mapping
  - [ ] Update Allow policy to include routing context
  - [ ] Deploy updated Lambda to DEV

- [ ] **Secrets Manager Setup**
  - [ ] Create `pagw/dev/totalview-service-token` secret
  - [ ] Get service token from TotalView team
  - [ ] Test token retrieval from Lambda

- [ ] **Testing**
  - [ ] Test token introspection with valid token
  - [ ] Test with expired token (verify rejection)
  - [ ] Test with invalid token (verify rejection)
  - [ ] Verify routing context in Lambda response

### Medium Priority (Week 2)

- [ ] **Orchestrator Integration**
  - [ ] Add ProviderContext model class
  - [ ] Update OrchestratorController to extract provider headers
  - [ ] Update request_tracker with provider fields (provider_id, provider_name, provider_npi)
  - [ ] Add routing logic based on requiresConverter/requiresAcmpMapping flags
  - [ ] Update workflow to handle ACMP_MAPPER stage

- [ ] **Request Tracker Schema**
  - [ ] Verify provider_id, provider_name, provider_npi columns exist
  - [ ] If not, create migration V005__add_provider_fields.sql
  - [ ] Add mapping_source column (converter, acmp_api, none)

- [ ] **API Gateway Configuration**
  - [ ] Configure Lambda Authorizer in API Gateway
  - [ ] Set up header mappings from Lambda context
  - [ ] Configure authorization caching
  - [ ] Test end-to-end flow

### Low Priority (Week 3-4)

- [ ] **ACMP Mapper Service** (if ACMP providers identified)
  - [ ] Create pasacmpmapper microservice
  - [ ] Implement ACMP mapping API client
  - [ ] Add SQS listener for acmp-mapper-queue
  - [ ] Add outbox routing to business-validator-queue
  - [ ] Deploy and test

- [ ] **Provider Registry Enhancement**
  - [ ] Add tenant column to provider_registry (if doesn't exist)
  - [ ] Add payer_system column (carelon, acmp, bcbsa)
  - [ ] Add mapping_source column
  - [ ] Populate with test providers

- [ ] **Monitoring & Logging**
  - [ ] Add CloudWatch dashboard for Lambda authorizer
  - [ ] Track token introspection success/failure rates
  - [ ] Alert on high rejection rates
  - [ ] Monitor routing decisions (converter vs ACMP)

### Documentation

- [ ] **Integration Guide**
  - [ ] Document TotalView registration process (for testing)
  - [ ] Document how to get test tokens
  - [ ] Document Lambda authorizer flow
  - [ ] Document routing logic

- [ ] **Runbook**
  - [ ] Lambda authorization failures troubleshooting
  - [ ] Token introspection API errors
  - [ ] Provider context missing in orchestrator

---

## Open Questions

1. **ACMP Mapping API**: 
   - What is the actual ACMP API endpoint?
   - Authentication method?
   - Request/response format?
   - Tenant Determination**:
   - Confirmed: tenant field from TotalView introspection response
   - tenant=elevance → ACMP
   - tenant=carelon → Carelon endpoints
   - How to distinguish Elevance ACMP vs Elevance subsidiaries from token?
   - Is it in token claims? Entity type? Issuer URL?

3. **Service Token Rotation**:
   - How often does TotalView service token expire?
   - Auto-rotation process?

4. **Error Scenarios**:
   - What if TotalView introspection API is down?
   - Fallback mechanism?
   - Circuit breaker needed?

5. **Performance**:
   - Expected token introspection latency?
   - Cache introspection results?
   - How long to cache?

---

## Success Criteria

✅ **Phase 1 Complete When**:
- Lambda calls TotalView introspection API successfully
- Valid tokens are allowed, invalid/expired tokens rejected
- Routing context passed to orchestrator
- Unit tests passing

✅ **Phase 2 Complete When**:
- Orchestrator receives provider context from Lambda
- request_tracker populated with provider info
- Routing works for Carelon providers (converter flow)
- End-to-end test successful in DEV

✅ **Phase 3 Complete When** (if ACMP identified):
- ACMP mapping service implemented
- ElevanceHealth ACMP providers routed correctly
- Mapping API integration working
- Performance acceptable (<500ms total auth + routing)
