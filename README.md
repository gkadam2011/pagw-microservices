# PAGW Microservices

> **Prior Authorization Gateway - Microservices Repository**

This repository contains the microservices for the PAGW platform. All services pull `pagwcore` from **JFrog Artifactory** - no more rebuilding all images when core changes!

---

## 🏗️ Architecture - JFrog Approach

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PAGW Platform Architecture                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────┐     ┌─────────────────────────────────────────┐   │
│  │  pagw-platform-core │     │          pagw-microservices             │   │
│  │                     │     │                                         │   │
│  │  pagwcore (JAR)     │────▶│  All services pull pagwcore from JFrog │   │
│  │  Published to JFrog │     │                                         │   │
│  └─────────────────────┘     │  ┌─────────────┐  ┌─────────────┐      │   │
│           │                  │  │pasorchest..│  │pasrequest.. │      │   │
│           ▼                  │  └─────────────┘  └─────────────┘      │   │
│  ┌─────────────────────┐     │  ┌─────────────┐  ┌─────────────┐      │   │
│  │   JFrog Artifactory │     │  │pasbusiness.│  │pasattach... │      │   │
│  │                     │     │  └─────────────┘  └─────────────┘      │   │
│  │ libs-release        │     │  ┌─────────────┐  ┌─────────────┐      │   │
│  │ libs-snapshot       │     │  │outboxpub.. │  │pasresponse..│      │   │
│  └─────────────────────┘     │  └─────────────┘  └─────────────┘      │   │
│                              └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Benefits of JFrog Approach

| Before (Docker Copy) | After (JFrog) |
|---------------------|---------------|
| ❌ Change pagwcore → rebuild ALL service images | ✅ Change pagwcore → publish JAR only |
| ❌ Long CI/CD pipelines | ✅ Fast independent builds |
| ❌ Tightly coupled | ✅ Version-pinned dependencies |
| ❌ No version control for core | ✅ Semantic versioning |

---

## 📁 Repository Structure

```
pagw-microservices/
├── pasorchestrator/             # Entry point - accepts PA requests
├── pasrequestparser/            # FHIR bundle parsing
├── pasbusinessvalidator/        # Business rules validation  
├── pasrequestenricher/          # Data enrichment
├── pasattachmenthandler/        # Attachment processing
├── pascanonnicalmapper/         # FHIR to X12 278 mapping
├── pasapiorchestrator/          # External API orchestration
├── pasresponsebuilder/          # Build final ClaimResponse
├── pascallbackhandler/          # Provider webhook notifications
├── outboxpublisher/             # Reliable event publishing (Outbox Pattern)
├── pom.xml                      # Parent POM with JFrog repos
└── Makefile                     # Build automation
```

Each service follows this layout:
```
service-name/
├── tekton/
│   └── config-dev.json      # Tekton pipeline config (type: docker)
├── Dockerfile               # Multi-stage build with UBI8
├── README.md
└── source/
    ├── pom.xml              # Includes pagwcore dependency from JFrog
    └── src/
        └── main/
            ├── java/...
            └── resources/
                └── application.yml
```

---

## 📊 Services Overview

| Service | Port | Description |
|---------|------|-------------|
| pasorchestrator | 8080 | Entry point, generates pagwId, writes to outbox |
| pasrequestparser | 8081 | FHIR bundle parsing |
| pasbusinessvalidator | 8082 | Business rules validation |
| pasrequestenricher | 8083 | Member/provider data enrichment |
| pasattachmenthandler | 8084 | Attachment processing & S3 uploads |
| pascanonnicalmapper | 8085 | FHIR→X12 278 mapping |
| pasapiorchestrator | 8086 | External API gateway orchestration |
| pasresponsebuilder | 8087 | Build ClaimResponse |
| pascallbackhandler | 8088 | Provider webhook notifications |
| outboxpublisher | 8089 | Polls outbox table, publishes to SQS |

---

## 🚀 Build & Deploy

### 1. Publishing pagwcore to JFrog (from pagw-platform-core)
```bash
cd pagw-platform-core/pagwcore/source
mvn clean deploy -DskipTests
```

### 2. Building All Microservices (pulls pagwcore from JFrog)
```bash
cd pagw-microservices
mvn clean package -DskipTests
```

### 3. Building Individual Service
```bash
cd pasorchestrator/source
mvn clean package -DskipTests
```

---

## 🔧 JFrog Configuration

### Service pom.xml (automatically configured)
```xml
<dependency>
    <groupId>com.anthem.pagw</groupId>
    <artifactId>pagwcore</artifactId>
    <version>${pagwcore.version}</version>
</dependency>

<repositories>
    <repository>
        <id>anthem-jfrog-releases</id>
        <url>https://antm.jfrog.io/artifactory/libs-release</url>
    </repository>
    <repository>
        <id>anthem-jfrog-snapshots</id>
        <url>https://antm.jfrog.io/artifactory/libs-snapshot</url>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>
```

### Maven settings.xml (~/.m2/settings.xml)
```xml
<servers>
    <server>
        <id>anthem-jfrog-releases</id>
        <username>${env.JFROG_USER}</username>
        <password>${env.JFROG_TOKEN}</password>
    </server>
    <server>
        <id>anthem-jfrog-snapshots</id>
        <username>${env.JFROG_USER}</username>
        <password>${env.JFROG_TOKEN}</password>
    </server>
</servers>
```

---

## 🔄 Updating pagwcore Version

To update all services to a new pagwcore version:
```bash
# Update single property across all pom.xml files
mvn versions:set-property -Dproperty=pagwcore.version -DnewVersion=1.0.1

# Or manually edit pagwcore.version in each pom.xml
```

---

## 🔧 Tekton Pipeline

Each service uses `type: docker` for Tekton builds:
```json
{
  "type": "docker",
  "dockerfile": "pasorchestrator/Dockerfile",
  "context": "pasorchestrator"
}
```

---

## 📝 License

Internal use only - Elevance Health
