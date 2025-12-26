# ==============================================================================
# PAGW Core Library - Multi-stage Dockerfile
# ==============================================================================
# Build: docker build -t pagwcore:latest .
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build the core library
# ------------------------------------------------------------------------------
FROM quay-nonprod.elevancehealth.com/multiarchitecture-golden-base-images/ubi8-openjdk:openjdk-17 AS builder

USER 0

WORKDIR /build

# Install Maven
RUN microdnf install -y maven && microdnf clean all

# Copy pom.xml first for dependency caching
COPY source/pom.xml ./pom.xml

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B || true

# Copy source code
COPY source/src ./src

# Build and install to local repository
RUN mvn clean install -DskipTests -B

# ------------------------------------------------------------------------------
# Stage 2: Package JARs for distribution
# ------------------------------------------------------------------------------
FROM quay-nonprod.elevancehealth.com/multiarchitecture-golden-base-images/ubi8-openjdk:openjdk-17 AS runtime

USER 0

WORKDIR /app/libs

# Copy built JARs (main jar, sources jar, javadoc jar)
COPY --from=builder /build/target/*.jar ./

# Copy Maven local repository for downstream builds
COPY --from=builder /root/.m2/repository/com/anthem/pagw /app/m2/com/anthem/pagw

# Labels for image identification
LABEL maintainer="PAGW Platform Team"
LABEL version="1.0.0-SNAPSHOT"
LABEL description="PAGW Core Library - Shared models, utilities, and AWS integrations"

# Default command - list available JARs
CMD ["ls", "-la", "/app/libs"]
