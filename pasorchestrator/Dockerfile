# ========================================================================
# PAS Orchestrator - Multi-stage Dockerfile
# ========================================================================
# Build args for version control - can be overridden at build time:
#   docker build --build-arg JAVA_VERSION=21 --build-arg SPRING_BOOT_VERSION=3.4.0 .
# ========================================================================

ARG JAVA_VERSION=17
ARG SPRING_BOOT_VERSION=3.3.0
ARG BASE_REGISTRY=quay-nonprod.elevancehealth.com/multiarchitecture-golden-base-images

# -----------------------------------------------------------------------------
# Stage 0: Pull pagwcore library from its image
# -----------------------------------------------------------------------------
FROM quay-nonprod.elevancehealth.com/pagw/crln-shared-dev-img-pagwcore:latest AS pagwcore

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/ubi8-openjdk:openjdk-${JAVA_VERSION} AS builder

USER 0

WORKDIR /build

# Install Maven
RUN microdnf install -y maven && microdnf clean all

# Copy pagwcore Maven artifacts (JARs and POMs) to local Maven repository
ARG SPRING_BOOT_VERSION
COPY --from=pagwcore /app/m2/ /root/.m2/repository/

# Debug: Show what was copied
RUN echo "=== PAGWCORE FILES ===" && find /root/.m2/repository/com/anthem/pagw -type f \( -name "*.jar" -o -name "*.pom" \) 2>/dev/null || echo "No artifacts found"

# Copy POM and download dependencies
COPY source/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B -Dspring-boot.version=${SPRING_BOOT_VERSION} || true

# Copy source and build
COPY source/src ./src
RUN mvn clean package -DskipTests -B -Dspring-boot.version=${SPRING_BOOT_VERSION}

# -----------------------------------------------------------------------------
# Runtime
# -----------------------------------------------------------------------------
ARG JAVA_VERSION
ARG BASE_REGISTRY
FROM ${BASE_REGISTRY}/ubi8-openjdk:openjdk-${JAVA_VERSION} AS runtime

USER 0

RUN groupadd -g 1001 appgroup && useradd -u 1001 -g appgroup appuser

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
