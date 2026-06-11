#!/usr/bin/env bash
# One-time TLS bootstrap: issues the first Let's Encrypt certificate so nginx can start with HTTPS.
# Run from the repo root on the server:  bash deploy/init-letsencrypt.sh
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root

# Load DOMAIN + LETSENCRYPT_EMAIL from the root .env
if [ -f ./.env ]; then set -a; . ./.env; set +a; fi
: "${DOMAIN:?Set DOMAIN in ./.env}"
: "${LETSENCRYPT_EMAIL:?Set LETSENCRYPT_EMAIL in ./.env}"

CERTBOT="./deploy/certbot"
RSA_KEY_SIZE=4096

echo "### Domain: $DOMAIN   Email: $LETSENCRYPT_EMAIL"
mkdir -p "$CERTBOT/conf" "$CERTBOT/www"

# 1) Recommended TLS params (referenced by the nginx config)
if [ ! -e "$CERTBOT/conf/options-ssl-nginx.conf" ] || [ ! -e "$CERTBOT/conf/ssl-dhparams.pem" ]; then
  echo "### Downloading recommended TLS parameters ..."
  curl -fsSL https://raw.githubusercontent.com/certbot/certbot/master/certbot-nginx/certbot_nginx/_internal/tls_configs/options-ssl-nginx.conf > "$CERTBOT/conf/options-ssl-nginx.conf"
  curl -fsSL https://raw.githubusercontent.com/certbot/certbot/master/certbot/certbot/ssl-dhparams.pem > "$CERTBOT/conf/ssl-dhparams.pem"
fi

# 2) Dummy cert so nginx can boot (it refuses to start without a cert file)
echo "### Creating dummy certificate ..."
mkdir -p "$CERTBOT/conf/live/$DOMAIN"
docker compose run --rm --entrypoint "\
  openssl req -x509 -nodes -newkey rsa:$RSA_KEY_SIZE -days 1 \
    -keyout '/etc/letsencrypt/live/$DOMAIN/privkey.pem' \
    -out '/etc/letsencrypt/live/$DOMAIN/fullchain.pem' \
    -subj '/CN=localhost'" certbot

echo "### Starting nginx (web) ..."
docker compose up --force-recreate -d web

# 3) Drop the dummy and request the real certificate over HTTP-01
echo "### Removing dummy certificate ..."
docker compose run --rm --entrypoint "\
  rm -Rf /etc/letsencrypt/live/$DOMAIN && \
  rm -Rf /etc/letsencrypt/archive/$DOMAIN && \
  rm -Rf /etc/letsencrypt/renewal/$DOMAIN.conf" certbot

echo "### Requesting Let's Encrypt certificate ..."
docker compose run --rm --entrypoint "\
  certbot certonly --webroot -w /var/www/certbot \
    -d $DOMAIN \
    --email $LETSENCRYPT_EMAIL \
    --rsa-key-size $RSA_KEY_SIZE \
    --agree-tos --no-eff-email --force-renewal" certbot

echo "### Reloading nginx with the real certificate ..."
docker compose exec web nginx -s reload

echo "### Done. Bring the full stack up with:  docker compose up -d --build"
