# PAGW Core

Core library for Prior Authorization Gateway (PAGW) - shared models, utilities, and AWS integrations.

## Overview

This is a **single flat JAR** that all PAGW microservices depend on. Published to JFrog Artifactory.

```
com.anthem.pagw:pagwcore:1.0.0-SNAPSHOT
```

## Structure

```
pagwcore/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/anthem/pagw/core/
│   │   │   ├── PagwCoreAutoConfiguration.java   # Spring Boot auto-config
│   │   │   ├── PagwProperties.java              # Configuration properties
│   │   │   ├── model/
│   │   │   │   ├── RequestTracker.java          # Core request model
│   │   │   │   ├── OutboxEntry.java             # Outbox pattern model
│   │   │   │   ├── PagwMessage.java             # SQS message wrapper
│   │   │   │   └── WorkflowDefinition.java      # Workflow config
│   │   │   ├── service/
│   │   │   │   ├── SecretsService.java          # AWS Secrets Manager
│   │   │   │   ├── SqsService.java              # AWS SQS operations
│   │   │   │   ├── S3Service.java               # AWS S3 operations
│   │   │   │   └── IdempotencyService.java      # DynamoDB idempotency
│   │   │   ├── util/
│   │   │   │   ├── PagwIdGenerator.java         # Unique ID generator
│   │   │   │   └── JsonUtils.java               # JSON utilities
│   │   │   └── exception/
│   │   │       ├── PagwException.java           # Base exception
│   │   │       ├── ValidationException.java
│   │   │       └── DownstreamException.java
│   │   └── resources/
│   │       └── META-INF/spring/...              # Auto-configuration
│   └── test/
└── README.md
```

## Usage

### Add Dependency

```xml
<dependency>
    <groupId>com.anthem.pagw</groupId>
    <artifactId>pagwcore</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<repositories>
    <repository>
        <id>anthem-jfrog-snapshots</id>
        <url>https://antm.jfrog.io/artifactory/libs-snapshot</url>
    </repository>
</repositories>
```

### Configuration

```yaml
pagw:
  application-id: my-service
  aws:
    region: us-east-1
    sqs:
      enabled: true
    s3:
      enabled: true
      attachment-bucket: pagw-attachments
    secrets:
      enabled: true
      aurora-secret-name: pagw/aurora/credentials
    dynamodb:
      enabled: true
      idempotency-table: pagw-idempotency
  queues:
    orchestrator: pagw-orchestrator-queue
    parser: pagw-parser-queue
```

### Auto-configured Beans

When you add pagwcore as a dependency, these beans are auto-configured:

- `SqsClient` - AWS SQS client
- `S3Client` - AWS S3 client
- `SecretsManagerClient` - AWS Secrets Manager client
- `DynamoDbClient` - AWS DynamoDB client (if enabled)
- `SqsService` - Higher-level SQS operations
- `S3Service` - Higher-level S3 operations
- `SecretsService` - Secrets fetching with caching
- `IdempotencyService` - Duplicate request prevention

### Generate PAGW IDs

```java
import com.anthem.pagw.core.util.PagwIdGenerator;

String pagwId = PagwIdGenerator.generate();
// Output: PAGW-20251219-00001-A1B2C3D4
```

### JSON Utils

```java
import com.anthem.pagw.core.util.JsonUtils;

String json = JsonUtils.toJson(myObject);
MyClass obj = JsonUtils.fromJson(json, MyClass.class);
```

## Build & Publish

```bash
# Build
mvn clean package

# Run tests
mvn test

# Publish to JFrog
mvn deploy
```

## Version History

- **1.0.0-SNAPSHOT** - Initial release with core models, AWS services, and utilities
