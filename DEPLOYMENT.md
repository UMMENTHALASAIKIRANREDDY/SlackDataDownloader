# Deployment Guide

Deploy the Slack DM Export tool (Spring Boot backend + React/Vite frontend) to an
Ubuntu/Debian server using Docker Compose, with HTTPS via Let's Encrypt and
automatic deploys from GitHub Actions on every push to `main`.

---

## Architecture

```
                 Internet (HTTPS :443)
                        │
                 ┌──────▼───────┐
                 │   web (nginx) │  serves the React build,
                 │   container   │  proxies /api → backend, terminates TLS
                 └──────┬───────┘
                        │ /api  (internal Docker network)
                 ┌──────▼───────┐
                 │   backend     │  Spring Boot jar, :8081 (internal only)
                 │   container   │
                 └──────┬───────┘
                        │ writes ZIPs
                 ┌──────▼───────┐
                 │ backend_exports volume │
                 └──────────────┘

 certbot container renews the TLS cert automatically every 12h.
```

| File | Purpose |
|---|---|
| `docker-compose.yml` | Defines `backend`, `web`, `certbot` services |
| `backend/Dockerfile` | Multi-stage Maven build → JRE runtime |
| `frontend/Dockerfile` | Vite build → nginx serving SPA + `/api` proxy |
| `frontend/nginx/app.conf.template` | nginx vhost (HTTP→HTTPS, SPA, API proxy) |
| `deploy/init-letsencrypt.sh` | One-time TLS certificate bootstrap |
| `deploy/deploy.sh` | Pull + rebuild + restart (used by CI and manually) |
| `.github/workflows/cicd.yml` | Build/validate, then SSH-deploy to the server |
| `.env` (root) | `DOMAIN`, `LETSENCRYPT_EMAIL` (compose/TLS) — **not committed** |
| `backend/.env` | Slack tokens + backend config — **not committed** |

---

## Prerequisites

1. **A server** running Ubuntu 20.04/22.04/24.04 (or Debian) with **SSH access** and `sudo`.
2. **A domain name** with a **DNS A record** pointing at the server's public IP.
   - e.g. `slackexport.yourcompany.com  →  203.0.113.10`
   - Verify it resolves before issuing TLS: `dig +short slackexport.yourcompany.com`
3. **Firewall**: ports **80** and **443** open to the internet (and **22** for SSH).
   - On most clouds this is a Security Group / firewall rule.
   - On the server itself, if `ufw` is active:
     ```bash
     sudo ufw allow 22 && sudo ufw allow 80 && sudo ufw allow 443
     ```
4. Your **Slack tokens** (the ones currently in your local `backend/.env`).

---

## Part A — One-time server setup

### A1. SSH into the server
```bash
ssh youruser@SERVER_IP
```

### A2. Install Docker Engine + Compose plugin
```bash
# Remove old versions (ignore errors)
sudo apt-get remove -y docker docker-engine docker.io containerd runc 2>/dev/null || true

# Install Docker's official repo
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Run docker without sudo (log out/in afterwards for it to take effect)
sudo usermod -aG docker $USER
newgrp docker

# Verify
docker --version
docker compose version
```
> **Debian note:** replace `ubuntu` with `debian` in the two URLs above.

### A3. Clone the repository
```bash
sudo mkdir -p /opt/slackdownloader
sudo chown $USER:$USER /opt/slackdownloader
git clone https://github.com/UMMENTHALASAIKIRANREDDY/SlackDataDownloader.git /opt/slackdownloader
cd /opt/slackdownloader
```
> If the repo is **private**, see [Appendix: private repo access](#appendix-private-repo-access).

### A4. Create the environment files (these hold secrets and are gitignored)

**Backend secrets/config:**
```bash
cp backend/.env.example backend/.env
nano backend/.env      # paste your real SLACK_ADMIN_TOKEN / SLACK_USER_TOKEN, save
```

**TLS / domain config (root `.env`):**
```bash
cp .env.example .env
nano .env              # set DOMAIN and LETSENCRYPT_EMAIL, save
```
Example root `.env`:
```
DOMAIN=slackexport.yourcompany.com
LETSENCRYPT_EMAIL=you@yourcompany.com
```

### A5. Build the images
```bash
docker compose build
```

### A6. Issue the TLS certificate (one time)
Make sure your DNS A record already points at this server, then:
```bash
bash deploy/init-letsencrypt.sh
```
This creates a temporary cert, starts nginx, then obtains the real Let's Encrypt
certificate over HTTP-01 and reloads nginx.

> If it fails with a challenge error, your domain isn't pointing at the server yet
> (DNS not propagated) or ports 80/443 are blocked. Fix those and re-run.

### A7. Start everything
```bash
docker compose up -d
docker compose ps          # all services should be "running"
```

Open **https://your-domain** in a browser. You should see the app, and exports should work.

---

## Part B — Set up automatic deploys from GitHub

On every push to `main`, GitHub Actions will build/validate, then SSH into the
server and run `deploy/deploy.sh` (pull + rebuild + restart).

### B1. Create an SSH key dedicated to deployments
On **your local machine** (not the server):
```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ./deploy_key -N ""
```
This makes `deploy_key` (private) and `deploy_key.pub` (public).

### B2. Authorize the key on the server
Copy the **public** key to the server's `authorized_keys` for the deploy user:
```bash
ssh-copy-id -i ./deploy_key.pub youruser@SERVER_IP
# or manually:  cat deploy_key.pub | ssh youruser@SERVER_IP 'cat >> ~/.ssh/authorized_keys'
```

### B3. Add the secrets to GitHub
In the repo: **Settings → Secrets and variables → Actions → New repository secret**.
Add these:

| Secret | Value |
|---|---|
| `SSH_HOST` | server public IP or hostname |
| `SSH_USER` | the SSH user (e.g. `ubuntu`) |
| `SSH_PORT` | `22` (or your custom SSH port) |
| `SSH_PRIVATE_KEY` | **entire contents** of the local `deploy_key` file (private key) |
| `DEPLOY_PATH` | `/opt/slackdownloader` |

> Paste the private key including the `-----BEGIN ...-----` / `-----END ...-----` lines.
> Then delete the local `deploy_key`/`deploy_key.pub` files once it's working.

### B4. Make sure the server can `git pull`
- **Public repo:** nothing to do.
- **Private repo:** see [Appendix](#appendix-private-repo-access).

### B5. Trigger it
Push any commit to `main` (or run the workflow manually from the **Actions** tab →
*CI/CD* → *Run workflow*). Watch it under the **Actions** tab. On success the server
is updated automatically.

---

## Day-to-day operations

**Manual redeploy (on the server):**
```bash
cd /opt/slackdownloader && bash deploy/deploy.sh
```

**View logs:**
```bash
docker compose logs -f backend       # backend
docker compose logs -f web           # nginx
docker compose logs -f certbot       # cert renewals
```

**Restart / stop / start:**
```bash
docker compose restart
docker compose down                  # stop & remove containers (keeps volumes)
docker compose up -d                 # start
```

**Where exports go:** inside the `backend_exports` Docker volume (auto-cleaned per
`EXPORT_CLEANUP_*` settings). Inspect with:
```bash
docker compose exec backend ls -la /app/exports
```

**Change backend config (tokens, timeouts, etc.):**
```bash
nano backend/.env
docker compose up -d backend         # recreate backend with new env
```

**TLS renewal:** automatic. The `certbot` container runs `certbot renew` every 12h;
certs renew within 30 days of expiry. No action needed.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `init-letsencrypt.sh` fails on the challenge | DNS A record not pointing at server yet, or ports 80/443 blocked. Check `dig +short DOMAIN` and your firewall/security group. |
| `502 Bad Gateway` from nginx | Backend not up yet or crashed. `docker compose logs backend`. |
| Backend logs "token is required" | `backend/.env` missing/empty tokens. Edit it, then `docker compose up -d backend`. |
| `port is already allocated` (80/443) | Something else (Apache/old nginx) is using them. `sudo lsof -i :80` and stop it. |
| GitHub Action SSH step fails | Check `SSH_HOST/USER/PORT` and that `SSH_PRIVATE_KEY` is the full private key. Test locally: `ssh -i deploy_key -p PORT user@host`. |
| Action deploy step: `git pull` permission denied | Private repo without server access — see Appendix. |

---

## Appendix: private repo access

If `SlackDataDownloader` is private, the **server** needs read access to pull it.
Use a read-only **deploy key**:

```bash
# On the server:
ssh-keygen -t ed25519 -C "server-deploy-key" -f ~/.ssh/github_deploy -N ""
cat ~/.ssh/github_deploy.pub
```
Add that public key in GitHub: **repo → Settings → Deploy keys → Add deploy key**
(read-only). Then tell git to use it and switch the remote to SSH:
```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  IdentityFile ~/.ssh/github_deploy
  IdentitiesOnly yes
EOF
cd /opt/slackdownloader
git remote set-url origin git@github.com:UMMENTHALASAIKIRANREDDY/SlackDataDownloader.git
git pull   # verify it works
```

---

## ⚠️ Security reminders

- The Slack tokens were previously committed to other repos and should be **rotated**
  in Slack (OAuth & Permissions → reinstall), then updated in `backend/.env`.
- Never commit `.env` or `backend/.env` — both are gitignored. Only the `.env.example`
  templates are in git.
- The `web` server only exposes 80/443; the backend's 8081 stays on the internal
  Docker network and is never reachable directly from the internet.
