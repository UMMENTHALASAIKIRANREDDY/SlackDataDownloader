#!/usr/bin/env bash
# Deploy with your OWN TLS certificate (no Let's Encrypt), serving on ports 80 + 443
# behind the domain in .env. Backend stays internal (localhost:8081 only).
#
# Prerequisites:
#   - Docker installed
#   - .env filled in (cp .env.example .env; set SLACK_*_TOKEN and DOMAIN)
#   - Certificate at deploy/ssl/tls.crt (cert + chain) and key at deploy/ssl/tls.key
#   - DNS A record for $DOMAIN points at this server; ports 80/443 open
#
# Run from the repo root:  bash deploy/deploy-server.sh
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { echo "ERROR: .env missing. Run: cp .env.example .env  then edit it."; exit 1; }
# Read only the values this script needs. Do NOT source .env: values like the cron
# expression contain spaces/asterisks and would be executed by the shell.
get_env() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
DOMAIN="$(get_env DOMAIN)"
VITE_API_BASE_URL="$(get_env VITE_API_BASE_URL)"
: "${DOMAIN:?ERROR: set DOMAIN in .env}"

if [ ! -f deploy/ssl/tls.crt ] || [ ! -f deploy/ssl/tls.key ]; then
  echo "ERROR: certificate not found."
  echo "  Put your cert (with chain) at: deploy/ssl/tls.crt"
  echo "  Put the private key at:        deploy/ssl/tls.key"
  exit 1
fi

echo "### [1/5] network + volume"
docker network create slacknet 2>/dev/null || true
docker volume create backend_exports >/dev/null

echo "### [2/5] build backend image"
docker build -t slackdownloader-backend ./backend

echo "### [3/5] build frontend image"
docker build -t slackdownloader-web --build-arg VITE_API_BASE_URL="${VITE_API_BASE_URL:-}" ./frontend

echo "### [4/5] run backend (internal, 127.0.0.1:8081)"
docker rm -f backend 2>/dev/null || true
docker run -d --name backend --network slacknet --restart unless-stopped \
  --env-file .env -e SERVER_PORT=8081 \
  -v backend_exports:/app/exports \
  -p 127.0.0.1:8081:8081 \
  slackdownloader-backend

echo "### [5/5] run web (nginx, 80 + 443, your cert)"
docker rm -f web 2>/dev/null || true
docker run -d --name web --network slacknet --restart unless-stopped \
  -p 80:80 -p 443:443 \
  -e DOMAIN="$DOMAIN" \
  -v "$(pwd)/deploy/nginx-customcert.conf.template:/etc/nginx/templates/default.conf.template:ro" \
  -v "$(pwd)/deploy/ssl:/etc/nginx/ssl:ro" \
  slackdownloader-web

echo
docker ps
echo
echo "### Done. Open: https://$DOMAIN"
