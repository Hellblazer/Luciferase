# Luciferase Distributed Simulation - Docker Image
#
# Build:
#   docker build -t luciferase:latest -f simulation/examples/docker/Dockerfile .
#
# Run:
#   docker run -p 9000:9000 -p 8080:8080 \
#     -e NODE_NAME=Node1 \
#     -e MY_PORT=9000 \
#     -e PEER_HOST=node2 \
#     -e PEER_PORT=9001 \
#     luciferase:latest

# Base image with Java 24
FROM eclipse-temurin:24-jdk-alpine AS builder

# Install Maven for build
RUN apk add --no-cache maven

# Set working directory
WORKDIR /build

# Copy project files
COPY pom.xml .
COPY common ./common
COPY grpc ./grpc
COPY lucien ./lucien
COPY render ./render
COPY sentry ./sentry
COPY portal ./portal
COPY von ./von
COPY simulation ./simulation
COPY gpu-test-framework ./gpu-test-framework
COPY resource ./resource
COPY dyada-java ./dyada-java
COPY e2e-test ./e2e-test

# Build the project (skip tests for faster build)
RUN mvn clean package -DskipTests -pl simulation -am

# Runtime stage - smaller image
FROM eclipse-temurin:24-jre-alpine

# Install runtime dependencies
RUN apk add --no-cache \
    curl \
    bash \
    tini

# Create application user (security best practice)
RUN addgroup -S luciferase && adduser -S luciferase -G luciferase

# Set working directory
WORKDIR /app

# Copy JAR and dependencies from builder
COPY --from=builder /build/simulation/target/simulation-*.jar /app/app.jar
COPY --from=builder /build/simulation/target/lib /app/lib

# Copy configuration files (optional)
COPY --chown=luciferase:luciferase simulation/examples/docker/config /app/config

# Create directories for data and logs
RUN mkdir -p /data /logs && \
    chown -R luciferase:luciferase /data /logs

# Switch to non-root user
USER luciferase

# Environment variables with defaults
ENV NODE_NAME=Node1 \
    MY_PORT=9000 \
    PEER_HOST=localhost \
    PEER_PORT=9001 \
    TLS_ENABLED=false \
    CERT_PATH=/certs/node-cert.pem \
    KEY_PATH=/certs/node-key.pem \
    CA_CERT_PATH=/certs/ca-cert.pem \
    LOG_LEVEL=INFO \
    JAVA_OPTS="-Xms2g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=20"

# Health check endpoint (requires HTTP server in application)
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Expose ports
# gRPC communication
EXPOSE 9000
# Health check / metrics
EXPOSE 8080
# Prometheus metrics
EXPOSE 9090

# Volume for persistent data
VOLUME ["/data", "/logs"]

# Use tini as init process (handles signals correctly)
ENTRYPOINT ["/sbin/tini", "--"]

# Start application
CMD ["sh", "-c", "java $JAVA_OPTS \
  -Dnode.name=$NODE_NAME \
  -Dnode.port=$MY_PORT \
  -Dpeer.host=$PEER_HOST \
  -Dpeer.port=$PEER_PORT \
  -Dtls.enabled=$TLS_ENABLED \
  -Dtls.cert=$CERT_PATH \
  -Dtls.key=$KEY_PATH \
  -Dtls.ca=$CA_CERT_PATH \
  -Dlog.level=$LOG_LEVEL \
  -jar /app/app.jar"]

# Metadata
LABEL maintainer="hal.hildebrand@me.com" \
      org.opencontainers.image.title="Luciferase Distributed Simulation" \
      org.opencontainers.image.description="Distributed volumetric animation with entity migration" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.source="https://github.com/Hellblazer/Luciferase" \
      org.opencontainers.image.licenses="AGPL-3.0"
