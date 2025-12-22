# ========================================================================
# PAS Subscription Handler - Multi-stage Dockerfile
# Build args: docker build --build-arg JAVA_VERSION=21 --build-arg SPRING_BOOT_VERSION=3.4.0 .
# ========================================================================

ARG JAVA_VERSION=17
ARG SPRING_BOOT_VERSION=3.3.0
ARG BASE_REGISTRY=quay-nonprod.elevancehealth.com/multiarchitecture-golden-base-images
ARG PAGWCORE_VERSION=latest

# -----------------------------------------------------------------------------
# Stage 0: Pull pagwcore library from its image
# -----------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/pagwcore:${PAGWCORE_VERSION} AS pagwcore

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/ubi8-openjdk:openjdk-${JAVA_VERSION} AS builder
ARG SPRING_BOOT_VERSION
USER 0
WORKDIR /build
RUN microdnf install -y maven && microdnf clean all

# Copy pagwcore from its image to local Maven repository
COPY --from=pagwcore /app/m2/com/anthem/pagw /root/.m2/repository/com/anthem/pagw

COPY source/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B -Dspring-boot.version=${SPRING_BOOT_VERSION} || true
COPY source/src ./src
RUN mvn clean package -DskipTests -B -Dspring-boot.version=${SPRING_BOOT_VERSION}

# -----------------------------------------------------------------------------
# Stage 2: Runtime
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
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8090/actuator/health || exit 1
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
