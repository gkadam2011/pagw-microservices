# PAS Provider Auth - Lambda Authorizer

AWS Lambda Authorizer for API Gateway that validates provider tokens and authorizes API access.

## Overview

This service acts as a gatekeeper for the PAGW API, validating incoming requests before they reach the Orchestrator. It supports multiple provider tenants:

- **Carelon** - OAuth2 JWT tokens
- **Elevance** - OAuth2 JWT tokens
- **BCBSA** - OAuth2 JWT tokens
- **Custom** - API Key authentication

## Architecture

```
Provider → API Gateway → Lambda Authorizer → Orchestrator
                ↓
        Aurora PostgreSQL
        (provider_registry)
```

## Flow

1. Provider sends request with `Authorization: Bearer <token>` header
2. API Gateway invokes Lambda Authorizer
3. Authorizer validates token (JWT signature, expiration)
4. Authorizer looks up provider in `provider_registry` table
5. Authorizer checks permissions for requested API resource
6. Returns IAM policy (Allow/Deny) + context to API Gateway
7. API Gateway forwards request to Orchestrator with provider context

## Database Schema

The service uses the `provider_registry` table in Aurora PostgreSQL:

```sql
CREATE TABLE provider_registry (
    client_id       TEXT PRIMARY KEY,
    tenant          TEXT NOT NULL,        -- carelon, elevance, bcbsa
    provider_name   TEXT NOT NULL,
    provider_type   TEXT NOT NULL,        -- PAYER, PROVIDER, CLEARINGHOUSE
    npi             TEXT,
    active          BOOLEAN DEFAULT true,
    permissions     TEXT[],               -- {"/Claim/*", "/Status/*"}
    rate_limit      INTEGER DEFAULT 100,
    issuer_url      TEXT,
    environment     TEXT DEFAULT 'dev',
    ...
);
```

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ENVIRONMENT` | No | dev, perf, prod (default: dev) |
| `AWS_REGION` | No | AWS region (default: us-east-1) |
| `RDS_PROXY_ENDPOINT` | Yes | RDS Proxy endpoint hostname |
| `DB_NAME` | No | Database name (default: pagw) |
| `DB_SECRET_ARN` | Yes | Secrets Manager ARN for DB credentials |

## IAM Permissions

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "<DB_SECRET_ARN>"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ec2:CreateNetworkInterface",
        "ec2:DescribeNetworkInterfaces",
        "ec2:DeleteNetworkInterface"
      ],
      "Resource": "*"
    }
  ]
}
```

## API Gateway Configuration

### Token Authorizer (Recommended)

```yaml
Type: AWS::ApiGateway::Authorizer
Properties:
  Name: ProviderAuthorizer
  Type: TOKEN
  AuthorizerUri: !Sub arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${ProviderAuthFunction.Arn}/invocations
  AuthorizerResultTtlInSeconds: 300  # Cache for 5 minutes
  IdentitySource: method.request.header.Authorization
```

### Request Authorizer (Alternative)

```yaml
Type: AWS::ApiGateway::Authorizer
Properties:
  Name: ProviderAuthorizer
  Type: REQUEST
  AuthorizerUri: !Sub arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${ProviderAuthFunction.Arn}/invocations
  AuthorizerResultTtlInSeconds: 300
  IdentitySource: method.request.header.Authorization,method.request.header.X-API-Key
```

## Context Passed to Downstream

The authorizer passes provider context to downstream Lambda functions:

```json
{
  "clientId": "ab0470e6-53f0-440f-9696-0293b61bf33d",
  "tenant": "carelon",
  "providerName": "Perf Payer Non Member",
  "providerType": "PAYER",
  "npi": "1234567890",
  "permissions": "/Claim/*,/Status/*",
  "rateLimit": "100"
}
```

Access in Orchestrator:
```java
// From API Gateway event
String clientId = event.getRequestContext().getAuthorizer().get("clientId");
String tenant = event.getRequestContext().getAuthorizer().get("tenant");
```

## Registering a New Provider

Insert into `provider_registry`:

```sql
INSERT INTO provider_registry (
    client_id, tenant, provider_name, provider_type, 
    active, permissions, rate_limit, issuer_url, environment
) VALUES (
    'new-client-id',
    'elevance',
    'New Provider Organization',
    'PROVIDER',
    true,
    ARRAY['/Claim/*', '/Status/*'],
    100,
    'https://auth.provider.com',
    'prod'
);
```

## Build & Deploy

```bash
# Build
cd source
mvn clean package

# Deploy (creates Lambda with handler)
aws lambda create-function \
  --function-name pagw-provider-auth \
  --runtime java17 \
  --handler com.anthem.pagw.auth.ProviderAuthHandler::handleRequest \
  --role arn:aws:iam::ACCOUNT:role/pagw-provider-auth-role \
  --zip-file fileb://target/pasproviderauth-1.0.0-SNAPSHOT.jar \
  --timeout 10 \
  --memory-size 512 \
  --environment Variables="{ENVIRONMENT=dev,RDS_PROXY_ENDPOINT=xxx,DB_SECRET_ARN=xxx}"
```

## Testing

### Valid Token
```bash
curl -X POST https://api.example.com/Claim \
  -H "Authorization: Bearer <valid-jwt-token>" \
  -H "Content-Type: application/json" \
  -d '{"resourceType": "Claim", ...}'
```

### API Key
```bash
curl -X POST https://api.example.com/Claim \
  -H "X-API-Key: carelon:your-api-key" \
  -H "Content-Type: application/json" \
  -d '{"resourceType": "Claim", ...}'
```

## Dev Mode

In `ENVIRONMENT=dev`, unregistered providers are allowed with full access. This is for development only and should never be enabled in production.
