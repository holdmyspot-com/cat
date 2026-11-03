#!/usr/bin/env bash
# Runs the integration tests against the locally built service.
set -euo pipefail
./build/service --port 8080 &
service=$!
trap 'kill "$service"' EXIT
until curl -fs http://localhost:8080/health > /dev/null; do sleep 1; done
./build/integration-tests --base-url http://localhost:8080
