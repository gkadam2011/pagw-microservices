# ========================================================================
# PAS API Connector - Multi-stage Dockerfile
# ========================================================================
# Build Arguments - Override at build time:
#   docker build --build-arg JAVA_VERSION=21 --build-arg SPRING_BOOT_VERSION=3.4.0 .
# ========================================================================
ARG JAVA_VERSION=17
ARG SPRING_BOOT_VERSION=3.3.0
ARG BASE_REGISTRY=quay-nonprod.elevancehealth.com/multiarchitecture-golden-base-images
ARG CACHE_BUST=1

# -----------------------------------------------------------------------------
# Stage 0: Pull pagwcore library from its image
# -----------------------------------------------------------------------------
FROM quay-nonprod.elevancehealth.com/pagw/crln-shared-dev-img-pagwcore:latest AS pagwcore

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM ${BASE_REGISTRY}/ubi8-openjdk:openjdk-${JAVA_VERSION} AS builder

# Re-declare ARGs after FROM (they go out of scope)
ARG SPRING_BOOT_VERSION

USER 0
WORKDIR /build
RUN microdnf install -y maven && microdnf clean all

# Copy pagwcore from its image to local Maven repository
COPY --from=pagwcore /app/m2/com/anthem/pagw /root/.m2/repository/com/anthem/pagw

COPY source/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B || true
ARG CACHE_BUST
RUN echo "Cache bust: ${CACHE_BUST}"
COPY source/src ./src
RUN rm -rf target && \
    mvn clean package -DskipTests -B -Dspring-boot.version=${SPRING_BOOT_VERSION}

# Re-declare ARGs for runtime stage
ARG BASE_REGISTRY
ARG JAVA_VERSION
FROM ${BASE_REGISTRY}/ubi8-openjdk:openjdk-${JAVA_VERSION} AS runtime
USER 0
RUN groupadd -g 1001 appgroup && useradd -u 1001 -g appgroup appuser
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
RUN chown -R appuser:appgroup /app
USER appuser
EXPOSE 8086
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8086/actuator/health || exit 1
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
