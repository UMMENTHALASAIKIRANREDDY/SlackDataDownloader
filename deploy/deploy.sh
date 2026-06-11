#!/usr/bin/env bash
# Pull the latest code and rebuild/restart the stack. Called by CI over SSH, or run manually.
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root

echo "### Pulling latest main ..."
git fetch --all --prune
git reset --hard origin/main

echo "### Rebuilding and restarting containers ..."
docker compose build
docker compose up -d

echo "### Pruning dangling images ..."
docker image prune -f

echo "### Deployed. Status:"
docker compose ps
