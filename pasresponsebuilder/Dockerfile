FROM quay-nonprod.elevancehealth.com/multiarchitecture-golden-base-images/ubi8-openjdk:openjdk-17 AS builder
USER 0
WORKDIR /build
RUN microdnf install -y maven && microdnf clean all

# Configure Maven with JFrog credentials for pagwcore dependency
ARG JFROG_USER
ARG JFROG_TOKEN
RUN mkdir -p /root/.m2 && echo '<?xml version="1.0" encoding="UTF-8"?><settings><servers><server><id>anthem-jfrog-releases</id><username>'"${JFROG_USER}"'</username><password>'"${JFROG_TOKEN}"'</password></server><server><id>anthem-jfrog-snapshots</id><username>'"${JFROG_USER}"'</username><password>'"${JFROG_TOKEN}"'</password></server></servers></settings>' > /root/.m2/settings.xml

COPY source/pom.xml ./pom.xml
RUN mvn dependency:go-offline -B || true
COPY source/src ./src
RUN mvn clean package -DskipTests -B

FROM quay-nonprod.elevancehealth.com/multiarchitecture-golden-base-images/ubi8-openjdk:openjdk-17 AS runtime
USER 0
RUN groupadd -g 1001 appgroup && useradd -u 1001 -g appgroup appuser
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
RUN chown -R appuser:appgroup /app
USER appuser
EXPOSE 8087
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8087/actuator/health || exit 1
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
