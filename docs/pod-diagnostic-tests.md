# Pod Diagnostic Tests for AWS Connectivity

Run these tests from inside a PAGW application pod to troubleshoot AWS credential and connectivity issues.

---

## Create a Test Pod

Create a temporary debug pod using the same service account as the PAGW applications:

### Option 1: Quick AWS CLI Pod

```bash
# Create a pod with AWS CLI using the pagw-custom-sa service account
kubectl run aws-debug --rm -it \
  --image=amazon/aws-cli:latest \
  --serviceaccount=pagw-custom-sa \
  --namespace=pagw-srv-dev \
  --restart=Never \
  --command -- /bin/bash
```

### Option 2: Full Debug Pod (YAML)

Save this as `debug-pod.yaml`:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: pagw-debug
  namespace: pagw-srv-dev
  labels:
    app: pagw-debug
spec:
  serviceAccountName: pagw-custom-sa
  containers:
  - name: debug
    image: amazon/aws-cli:latest
    command: ["/bin/bash", "-c", "sleep 3600"]
    env:
    - name: AWS_REGION
      value: "us-east-2"
    - name: AWS_DEFAULT_REGION
      value: "us-east-2"
    resources:
      requests:
        memory: "128Mi"
        cpu: "100m"
      limits:
        memory: "256Mi"
        cpu: "200m"
  restartPolicy: Never
  terminationGracePeriodSeconds: 0
```

Apply and connect:

```bash
# Create the debug pod
kubectl apply -f debug-pod.yaml

# Wait for it to be ready
kubectl wait --for=condition=Ready pod/pagw-debug -n pagw-srv-dev --timeout=60s

# Connect to the pod
kubectl exec -it pagw-debug -n pagw-srv-dev -- /bin/bash

# When done, delete the pod
kubectl delete pod pagw-debug -n pagw-srv-dev
```

### Option 3: Debug Pod with Network Tools

For more comprehensive debugging (includes curl, nc, nslookup):

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: pagw-netdebug
  namespace: pagw-srv-dev
spec:
  serviceAccountName: pagw-custom-sa
  containers:
  - name: debug
    image: nicolaka/netshoot:latest
    command: ["/bin/bash", "-c", "sleep 3600"]
    env:
    - name: AWS_REGION
      value: "us-east-2"
  restartPolicy: Never
```

Then install AWS CLI inside:

```bash
kubectl exec -it pagw-netdebug -n pagw-srv-dev -- /bin/bash
# Inside the pod:
pip install awscli --quiet
aws --version
```

### Option 4: One-liner for quick STS check

```bash
# Quick identity check without keeping the pod
kubectl run sts-check --rm -it \
  --image=amazon/aws-cli:latest \
  --serviceaccount=pagw-custom-sa \
  --namespace=pagw-srv-dev \
  --restart=Never \
  -- sts get-caller-identity
```

---

## Prerequisites

```bash
# Get into a running pod
kubectl exec -it <pod-name> -n pagw-srv-dev -- /bin/sh

# If bash is available
kubectl exec -it <pod-name> -n pagw-srv-dev -- /bin/bash
```

---

## 1. IRSA Environment Variables

Check if IRSA (IAM Roles for Service Accounts) is properly configured:

```bash
# Check IRSA environment variables
echo "=== IRSA Environment Variables ==="
echo "AWS_ROLE_ARN: $AWS_ROLE_ARN"
echo "AWS_WEB_IDENTITY_TOKEN_FILE: $AWS_WEB_IDENTITY_TOKEN_FILE"
echo "AWS_REGION: $AWS_REGION"
echo "AWS_DEFAULT_REGION: $AWS_DEFAULT_REGION"

# List all AWS-related env vars
env | grep -i aws
```

**Expected Output:**
```
AWS_ROLE_ARN=arn:aws:iam::482754295601:role/CRLN-PAGW-dev-EKS-crossaccount-Role
AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/eks.amazonaws.com/serviceaccount/token
AWS_REGION=us-east-2
```

If these are NOT set, IRSA is not configured properly on the service account or pod.

---

## 2. Web Identity Token File

Verify the token file exists and is readable:

```bash
# Check if token file exists
echo "=== Web Identity Token ==="
ls -la /var/run/secrets/eks.amazonaws.com/serviceaccount/

# Check token file contents (JWT)
cat /var/run/secrets/eks.amazonaws.com/serviceaccount/token

# Decode JWT payload (if base64/jq available)
cat /var/run/secrets/eks.amazonaws.com/serviceaccount/token | cut -d. -f2 | base64 -d 2>/dev/null
```

**Expected:** A valid JWT token should exist at this path.

---

## 3. AWS CLI Identity Check

If AWS CLI is installed in the container:

```bash
# Check caller identity - this is the KEY test
echo "=== AWS Caller Identity ==="
aws sts get-caller-identity

# With explicit region
aws sts get-caller-identity --region us-east-2
```

**Expected Output:**
```json
{
    "UserId": "AROA...:aws-sdk-java-...",
    "Account": "482754295601",
    "Arn": "arn:aws:sts::482754295601:assumed-role/CRLN-PAGW-dev-EKS-crossaccount-Role/..."
}
```

---

## 4. Secrets Manager Test

Test access to the Aurora database secret:

```bash
echo "=== Secrets Manager Test ==="

# Get the secret value
aws secretsmanager get-secret-value \
    --secret-id "arn:aws:secretsmanager:us-east-2:482754295601:secret:dev/rds/aurora/postgresql/pagw-h63RqT" \
    --region us-east-2

# If the above fails, try listing secrets to see what's accessible
aws secretsmanager list-secrets --region us-east-2 | grep -i pagw
```

**Expected:** Should return the secret JSON with database credentials.

**If it fails:** Compare the error with what the Java app sees. Same error = IAM issue. Different error = code issue.

---

## 5. SQS Test

Test SQS queue access:

```bash
echo "=== SQS Tests ==="

# Get queue URL
aws sqs get-queue-url \
    --queue-name "dev-PAGW-pagw-orchestrator-queue.fifo" \
    --region us-east-2

# Get queue attributes
aws sqs get-queue-attributes \
    --queue-url "https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo" \
    --attribute-names All \
    --region us-east-2

# Try to receive a message (non-destructive peek)
aws sqs receive-message \
    --queue-url "https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo" \
    --max-number-of-messages 1 \
    --visibility-timeout 0 \
    --region us-east-2

# Test sending a message
aws sqs send-message \
    --queue-url "https://sqs.us-east-2.amazonaws.com/482754295601/dev-PAGW-pagw-orchestrator-queue.fifo" \
    --message-body '{"test": "diagnostic"}' \
    --message-group-id "diagnostic-test" \
    --message-deduplication-id "test-$(date +%s)" \
    --region us-east-2
```

---

## 6. S3 Test

Test S3 bucket access:

```bash
echo "=== S3 Tests ==="

# List bucket contents
aws s3 ls s3://crln-pagw-dev-dataz-gbd-phi-useast2/ --region us-east-2

# Try to write a test file
echo "diagnostic test" | aws s3 cp - s3://crln-pagw-dev-dataz-gbd-phi-useast2/diagnostic/test.txt --region us-east-2

# Read it back
aws s3 cp s3://crln-pagw-dev-dataz-gbd-phi-useast2/diagnostic/test.txt - --region us-east-2

# Clean up
aws s3 rm s3://crln-pagw-dev-dataz-gbd-phi-useast2/diagnostic/test.txt --region us-east-2
```

---

## 7. Network Connectivity

Test if AWS endpoints are reachable:

```bash
echo "=== Network Connectivity ==="

# Test STS endpoint
curl -s -o /dev/null -w "%{http_code}" https://sts.us-east-2.amazonaws.com/

# Test Secrets Manager endpoint
curl -s -o /dev/null -w "%{http_code}" https://secretsmanager.us-east-2.amazonaws.com/

# Test SQS endpoint
curl -s -o /dev/null -w "%{http_code}" https://sqs.us-east-2.amazonaws.com/

# Test S3 endpoint
curl -s -o /dev/null -w "%{http_code}" https://s3.us-east-2.amazonaws.com/

# DNS resolution
nslookup secretsmanager.us-east-2.amazonaws.com
nslookup sqs.us-east-2.amazonaws.com
```

---

## 8. Database Connectivity

Test PostgreSQL connectivity (if psql is available):

```bash
echo "=== Database Connectivity ==="

# Test TCP connection to Aurora
nc -zv aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com 5432

# Or with timeout
timeout 5 bash -c 'cat < /dev/null > /dev/tcp/aapsql-apm1082022-00dev01.czq1gklw7b57.us-east-2.rds.amazonaws.com/5432' && echo "Connection successful" || echo "Connection failed"
```

---

## 9. Java-Specific Credential Check

If you can run Java commands in the pod:

```bash
# Check what the Java SDK sees for credentials
java -cp /app/app.jar -Dloader.main=com.anthem.pagw.DiagnosticRunner org.springframework.boot.loader.launch.PropertiesLauncher 2>/dev/null || echo "Custom runner not available"
```

Or use the diagnostic endpoint (if deployed):
```bash
curl http://localhost:8080/actuator/diagnostic/aws-identity
```

---

## 10. Compare SDK vs CLI Credentials

This is the critical comparison. Run AWS CLI and note the assumed role ARN, then check if the Java application log shows the same ARN.

```bash
# CLI identity
aws sts get-caller-identity --output text

# Check application logs for the ARN it's using
# The error message should show something like:
# "User: arn:aws:sts::482754295601:assumed-role/CRLN-PAGW-dev-EKS-crossaccount-Role/aws-sdk-java-..."
```

If CLI works but Java fails with the SAME role ARN, the issue is IAM policy.
If CLI and Java show DIFFERENT role ARNs, the issue is credential configuration.

---

## Summary Checklist

| Test | Command | Expected |
|------|---------|----------|
| IRSA env vars | `env \| grep AWS` | AWS_ROLE_ARN and token file set |
| Token file | `ls /var/run/secrets/eks.amazonaws.com/serviceaccount/` | token file exists |
| STS identity | `aws sts get-caller-identity` | Shows cross-account role |
| Secrets Manager | `aws secretsmanager get-secret-value --secret-id ...` | Returns secret JSON |
| SQS access | `aws sqs get-queue-url --queue-name ...` | Returns queue URL |
| S3 access | `aws s3 ls s3://bucket/` | Lists bucket contents |
| DB connectivity | `nc -zv <aurora-endpoint> 5432` | Connection open |

---

## Common Issues

### 1. IRSA Not Working
- Service account missing `eks.amazonaws.com/role-arn` annotation
- Pod not using the correct service account
- OIDC provider trust policy mismatch

### 2. IAM Policy Mismatch
- Resource ARN patterns don't match actual resources
- Missing required actions (e.g., `sqs:DeleteMessage` for message acknowledgment)
- Policy uses wildcards incorrectly

### 3. SDK Credential Chain
- Java SDK might pick up credentials differently than CLI
- Check for hardcoded credentials in application.yaml
- LocalStack configuration leaking into production

### 4. Missing SQS Permissions
The current policy allows: `SendMessage, GetQueueUrl, ReceiveMessage, GetQueueAttributes`
But may need: `DeleteMessage` (for acknowledging messages), `CreateQueue` (if auto-create is enabled)
