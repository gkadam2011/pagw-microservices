#!/bin/bash
# LocalStack initialization script
# Creates SQS queues and S3 buckets for local development

echo "Creating SQS queues..."
awslocal sqs create-queue --queue-name pagw-orchestrator-queue
awslocal sqs create-queue --queue-name pagw-request-parser-queue
awslocal sqs create-queue --queue-name pagw-business-validator-queue
awslocal sqs create-queue --queue-name pagw-attachment-handler-queue
awslocal sqs create-queue --queue-name pagw-request-enricher-queue
awslocal sqs create-queue --queue-name pagw-request-converter-queue
awslocal sqs create-queue --queue-name pagw-canonical-mapper-queue
awslocal sqs create-queue --queue-name pagw-api-connector-queue
awslocal sqs create-queue --queue-name pagw-response-builder-queue
awslocal sqs create-queue --queue-name pagw-callback-handler-queue
awslocal sqs create-queue --queue-name pagw-subscription-handler-queue
awslocal sqs create-queue --queue-name pagw-orchestrator-complete-queue
awslocal sqs create-queue --queue-name pagw-outbox-queue
awslocal sqs create-queue --queue-name pagw-dlq

echo "Creating S3 buckets..."
# Request bucket - stores all request lifecycle data (PHI)
# Structure: {YYYYMM}/{pagwId}/request|response|attachments|callbacks/
awslocal s3 mb s3://pagw-request-local

# Audit bucket - stores logs and operational data (NO PHI)
# Structure: {YYYYMM}/{YYYYMMDD}/{service}/{HH}/{pagwId}_{uuid}.json
awslocal s3 mb s3://pagw-audit-local

echo "Creating KMS keys for encryption..."
# Create KMS key for S3 encryption (PHI data)
S3_KEY_ID=$(awslocal kms create-key --description "PAGW S3 encryption key" --query 'KeyMetadata.KeyId' --output text)
awslocal kms create-alias --alias-name alias/pagw-s3-key --target-key-id $S3_KEY_ID

# Create KMS key for SQS encryption
SQS_KEY_ID=$(awslocal kms create-key --description "PAGW SQS encryption key" --query 'KeyMetadata.KeyId' --output text)
awslocal kms create-alias --alias-name alias/pagw-sqs-key --target-key-id $SQS_KEY_ID

echo "Enabling S3 bucket encryption with KMS..."
# Enable KMS encryption on request bucket (PHI data)
awslocal s3api put-bucket-encryption --bucket pagw-request-local \
    --server-side-encryption-configuration '{
        "Rules": [{
            "ApplyServerSideEncryptionByDefault": {
                "SSEAlgorithm": "aws:kms",
                "KMSMasterKeyID": "'$S3_KEY_ID'"
            },
            "BucketKeyEnabled": true
        }]
    }'

# Enable AES256 encryption on audit bucket (no PHI, less strict)
awslocal s3api put-bucket-encryption --bucket pagw-audit-local \
    --server-side-encryption-configuration '{
        "Rules": [{
            "ApplyServerSideEncryptionByDefault": {
                "SSEAlgorithm": "AES256"
            }
        }]
    }'

echo "S3 KMS Key ID: $S3_KEY_ID"
echo "SQS KMS Key ID: $SQS_KEY_ID"

echo "Creating secrets..."
awslocal secretsmanager create-secret \
    --name "pagw/local/database" \
    --secret-string '{"username":"pagw","password":"pagw123","host":"postgres","port":"5432","dbname":"pagw"}'

awslocal secretsmanager create-secret \
    --name "pagw/local/jwt-secret" \
    --secret-string '{"secret":"local-jwt-secret-key-for-testing-only-do-not-use-in-production"}'

awslocal secretsmanager create-secret \
    --name "pagw/local/payer-credentials" \
    --secret-string '{"carelon":{"client_id":"test","client_secret":"test"},"elevance":{"client_id":"test","client_secret":"test"}}'

echo "Creating DynamoDB idempotency table..."
awslocal dynamodb create-table \
    --table-name pagw-idempotency-local \
    --attribute-definitions \
        AttributeName=idempotencyKey,AttributeType=S \
    --key-schema \
        AttributeName=idempotencyKey,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --tags Key=Environment,Value=local Key=Purpose,Value=Idempotency

echo "LocalStack initialization complete!"
