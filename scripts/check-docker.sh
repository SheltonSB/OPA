#!/usr/bin/env bash
# Verify the host can run the Docker-backed application and Testcontainers.
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is required. Install Docker Desktop or Docker Engine." >&2
  exit 2
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker CLI is installed, but the Docker daemon is unavailable." >&2
  echo "Start Docker Desktop/Engine and retry." >&2
  exit 2
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required (docker compose)." >&2
  exit 2
fi

echo "Docker engine: $(docker version --format '{{.Server.Version}}')"
echo "Docker Compose: $(docker compose version --short)"

if [[ "${CHECK_COMPOSE_CONFIG:-0}" == "1" ]]; then
  docker compose config --quiet
  echo "Compose configuration: valid"
fi
