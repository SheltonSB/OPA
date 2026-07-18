# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
# Verification runs before the image build in CI. Keeping tests out of this isolated
# BuildKit stage avoids silently skipping Docker-backed Testcontainers tests.
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -DskipTests package

FROM openpolicyagent/opa:1.18.2-static AS opa

FROM gcr.io/distroless/java21-debian12:nonroot
LABEL org.opencontainers.image.title="OPA Policy Performance Guard" \
      org.opencontainers.image.description="Distributed OPA policy regression platform" \
      org.opencontainers.image.source="https://github.com/SheltonSB/OPA" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=opa --chown=nonroot:nonroot /opa /usr/local/bin/opa
COPY --from=build --chown=nonroot:nonroot /workspace/target/opa-policy-performance-guard-*.jar /app/opa-guard.jar
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:+ExitOnOutOfMemoryError","-Djava.security.egd=file:/dev/urandom","-jar","/app/opa-guard.jar"]
