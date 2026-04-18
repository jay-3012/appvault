# Sprint 1 — Infrastructure Setup Guide

Complete step-by-step instructions for provisioning both GCP VMs, installing Docker,
configuring secrets, setting up GCS buckets, wiring CI/CD, and getting the backup cron running.

Follow every section in order. Do not skip ahead.

---

## Prerequisites (do these before anything else)

- GCP account with ₹28,444 credits active
- GitHub account with a repository created for the project (e.g. `appvault`)
- A domain name (even a free one from Freenom works for now)
- Your local machine has: `gcloud` CLI, `git`, `ssh-keygen`

### Install gcloud CLI (if not already installed)

```bash
# macOS
brew install --cask google-cloud-sdk

# Ubuntu / Debian
curl https://sdk.cloud.google.com | bash
exec -l $SHELL

# Verify
gcloud version
```

### Authenticate and set project

```bash
gcloud auth login
gcloud projects list          # note your project ID
gcloud config set project YOUR_PROJECT_ID
gcloud config set compute/region asia-south1   # Mumbai — closest to you
gcloud config set compute/zone asia-south1-a
```

---

## Step 1 — Enable required GCP APIs

Run this once. It enables the APIs needed for VMs, storage, and secrets.

```bash
gcloud services enable \
  compute.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  cloudresourcemanager.googleapis.com \
  iam.googleapis.com
```

Wait ~60 seconds after running. You should see `Operation finished successfully`.

---

## Step 2 — Create SSH key pair for VM access

This key will be used by you locally AND by GitHub Actions to deploy.

```bash
# Generate dedicated key for this project
ssh-keygen -t ed25519 -C "appvault-deploy" -f ~/.ssh/appvault_deploy

# View the public key — you'll need this in Steps 3 and 4
cat ~/.ssh/appvault_deploy.pub
```

Keep `~/.ssh/appvault_deploy` (private key) safe. Never commit it anywhere.

---

## Step 3 — Provision main-vm (GCP e2-medium)

This VM runs: Spring Boot app, PostgreSQL, Redis, Nginx.

```bash
gcloud compute instances create main-vm \
  --machine-type=e2-medium \
  --zone=asia-south1-a \
  --image-family=ubuntu-2404-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=30GB \
  --boot-disk-type=pd-standard \
  --tags=http-server,https-server \
  --metadata="ssh-keys=ubuntu:$(cat ~/.ssh/appvault_deploy.pub)"
```

Note the **EXTERNAL_IP** from the output. Save it — you'll use it everywhere.

```bash
# Verify SSH works
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_MAIN_VM_IP
```

You should land at a Ubuntu shell. Type `exit` to leave.

---

## Step 4 — Provision scanner-vm (GCP e2-small)

This VM runs: FastAPI scanner, MobSF, Celery worker, Nginx.

```bash
gcloud compute instances create scanner-vm \
  --machine-type=e2-small \
  --zone=asia-south1-a \
  --image-family=ubuntu-2404-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=20GB \
  --boot-disk-type=pd-standard \
  --tags=http-server,https-server \
  --metadata="ssh-keys=ubuntu:$(cat ~/.ssh/appvault_deploy.pub)"
```

Note the **EXTERNAL_IP** for scanner-vm as well.

```bash
# Verify SSH works
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_SCANNER_VM_IP
```

---

## Step 5 — Configure GCP firewall rules

Allow HTTP, HTTPS, and custom app ports.

```bash
# Allow HTTP and HTTPS traffic
gcloud compute firewall-rules create allow-http \
  --allow tcp:80 \
  --target-tags http-server \
  --description "Allow HTTP"

gcloud compute firewall-rules create allow-https \
  --allow tcp:443 \
  --target-tags https-server \
  --description "Allow HTTPS"

# Allow Spring Boot port (internal — only needed during dev, remove in prod)
gcloud compute firewall-rules create allow-springboot-dev \
  --allow tcp:8080 \
  --target-tags http-server \
  --description "Spring Boot dev access"

# Allow FastAPI scanner port (internal — only needed during dev)
gcloud compute firewall-rules create allow-fastapi-dev \
  --allow tcp:8000 \
  --target-tags http-server \
  --description "FastAPI dev access"
```

---

## Step 6 — Install Docker on main-vm

SSH into main-vm and run all of the following:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_MAIN_VM_IP
```

Once inside the VM:

```bash
# Update system
sudo apt-get update && sudo apt-get upgrade -y

# Install Docker dependencies
sudo apt-get install -y \
  ca-certificates \
  curl \
  gnupg \
  lsb-release

# Add Docker's official GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Add Docker repository
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker Engine + Compose plugin
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# Add ubuntu user to docker group (no sudo needed for docker commands)
sudo usermod -aG docker ubuntu

# Apply group change without logout
newgrp docker

# Verify Docker works
docker run hello-world
docker compose version
```

Expected output ends with: `Hello from Docker!` and a compose version like `Docker Compose version v2.x.x`

Type `exit` to leave the VM.

---

## Step 7 — Install Docker on scanner-vm

SSH into scanner-vm and run the exact same commands as Step 6:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_SCANNER_VM_IP
```

Then repeat the entire Docker installation block from Step 6 inside this VM.

---

## Step 8 — Create GCS buckets

Back on your local machine:

```bash
# Files bucket — APKs, screenshots, icons
gcloud storage buckets create gs://appvault-files \
  --location=asia-south1 \
  --uniform-bucket-level-access

# Backups bucket — pg_dump files
gcloud storage buckets create gs://appvault-backups \
  --location=asia-south1 \
  --uniform-bucket-level-access

# Verify both buckets exist
gcloud storage buckets list | grep appvault
```

### Remove public access from files bucket

```bash
# Ensure no public access (AllUsers should not have any role)
gcloud storage buckets set-iam-policy gs://appvault-files /dev/stdin << 'EOF'
{
  "bindings": []
}
EOF

# Verify: this should return an empty or restricted policy
gcloud storage buckets get-iam-policy gs://appvault-files
```

### Set lifecycle policy on backups bucket

Create a file called `lifecycle.json` on your local machine:

```json
{
  "rule": [
    {
      "action": { "type": "Delete" },
      "condition": {
        "age": 30,
        "matchesPrefix": ["postgres/"]
      }
    }
  ]
}
```

Apply it:

```bash
gcloud storage buckets update gs://appvault-backups \
  --lifecycle-file=lifecycle.json
```

---

## Step 9 — Create GCP Service Accounts

Two service accounts: one for the main app, one for the scanner.

```bash
# Service account for main-vm app
gcloud iam service-accounts create appvault-main \
  --display-name="AppVault Main App"

# Service account for scanner-vm
gcloud iam service-accounts create appvault-scanner \
  --display-name="AppVault Scanner"

# Store your project ID in a variable for convenience
PROJECT_ID=$(gcloud config get-value project)
```

### Grant main-vm service account permissions

```bash
# Read/write access to appvault-files bucket
gcloud storage buckets add-iam-policy-binding gs://appvault-files \
  --member="serviceAccount:appvault-main@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"

# Write access to appvault-backups bucket (for pg_dump uploads)
gcloud storage buckets add-iam-policy-binding gs://appvault-backups \
  --member="serviceAccount:appvault-main@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/storage.objectCreator"

# Read access to Secret Manager
gcloud projects add-iam-policy-binding ${PROJECT_ID} \
  --member="serviceAccount:appvault-main@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

### Grant scanner-vm service account permissions

```bash
# Read-only access to appvault-files bucket (to download APKs for scanning)
gcloud storage buckets add-iam-policy-binding gs://appvault-files \
  --member="serviceAccount:appvault-scanner@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/storage.objectViewer"

# Read access to Secret Manager (for HMAC secret)
gcloud projects add-iam-policy-binding ${PROJECT_ID} \
  --member="serviceAccount:appvault-scanner@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

### Create and download service account key files

```bash
# Key for main-vm
gcloud iam service-accounts keys create ./main-sa-key.json \
  --iam-account="appvault-main@${PROJECT_ID}.iam.gserviceaccount.com"

# Key for scanner-vm
gcloud iam service-accounts keys create ./scanner-sa-key.json \
  --iam-account="appvault-scanner@${PROJECT_ID}.iam.gserviceaccount.com"
```

These JSON files are credentials. They will be stored in Secret Manager in the next step — never commit them to git.

---

## Step 10 — Set up GCP Secret Manager

### Create all secrets

```bash
PROJECT_ID=$(gcloud config get-value project)

# DB password — generate a strong random one
DB_PASSWORD=$(openssl rand -base64 32 | tr -dc 'a-zA-Z0-9' | head -c 32)
echo "SAVE THIS DB PASSWORD: ${DB_PASSWORD}"

# JWT signing key — long random string
JWT_SECRET=$(openssl rand -base64 64 | tr -dc 'a-zA-Z0-9' | head -c 64)
echo "SAVE THIS JWT SECRET: ${JWT_SECRET}"

# HMAC shared secret between main platform and scanner
HMAC_SECRET=$(openssl rand -base64 32 | tr -dc 'a-zA-Z0-9' | head -c 32)
echo "SAVE THIS HMAC SECRET: ${HMAC_SECRET}"

# Create secrets in Secret Manager
echo -n "${DB_PASSWORD}" | gcloud secrets create db-password \
  --data-file=- --replication-policy=automatic

echo -n "${JWT_SECRET}" | gcloud secrets create jwt-secret \
  --data-file=- --replication-policy=automatic

echo -n "${HMAC_SECRET}" | gcloud secrets create scanner-hmac-secret \
  --data-file=- --replication-policy=automatic

# GCS service account keys (from Step 9)
gcloud secrets create gcs-main-service-account \
  --data-file=./main-sa-key.json --replication-policy=automatic

gcloud secrets create gcs-scanner-service-account \
  --data-file=./scanner-sa-key.json --replication-policy=automatic

# Resend API key — get yours from resend.com (free signup, no card needed)
# Replace YOUR_RESEND_KEY with the actual key
echo -n "YOUR_RESEND_KEY" | gcloud secrets create resend-api-key \
  --data-file=- --replication-policy=automatic
```

### Verify all secrets exist

```bash
gcloud secrets list
```

You should see:
- `db-password`
- `jwt-secret`
- `scanner-hmac-secret`
- `gcs-main-service-account`
- `gcs-scanner-service-account`
- `resend-api-key`

### Delete local key files (they are now in Secret Manager)

```bash
rm ./main-sa-key.json ./scanner-sa-key.json
```

---

## Step 11 — Set up GitHub repository secrets

GitHub Actions needs credentials to deploy to both VMs. Go to your GitHub repo → Settings → Secrets and variables → Actions → New repository secret.

Add these secrets:

| Secret name | Value |
|---|---|
| `MAIN_VM_IP` | External IP of main-vm |
| `SCANNER_VM_IP` | External IP of scanner-vm |
| `SSH_PRIVATE_KEY` | Contents of `~/.ssh/appvault_deploy` (the private key) |
| `GCP_PROJECT_ID` | Your GCP project ID |

To get the SSH private key content:

```bash
cat ~/.ssh/appvault_deploy
```

Copy the entire output including `-----BEGIN OPENSSH PRIVATE KEY-----` and `-----END OPENSSH PRIVATE KEY-----` into the GitHub secret.

---

## Step 12 — Create Docker Compose files on each VM

### main-vm: create docker-compose.yml

SSH into main-vm:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_MAIN_VM_IP
mkdir -p /home/ubuntu/appvault
```

Create `/home/ubuntu/appvault/docker-compose.yml`:

```bash
cat > /home/ubuntu/appvault/docker-compose.yml << 'EOF'
version: "3.9"

services:
  postgres:
    image: postgres:15-alpine
    container_name: appvault-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: appvault
      POSTGRES_USER: appvault
      POSTGRES_PASSWORD_FILE: /run/secrets/db_password
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U appvault"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: appvault-redis
    restart: unless-stopped
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  app:
    image: ghcr.io/YOUR_GITHUB_USERNAME/appvault-backend:latest
    container_name: appvault-app
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/appvault
      SPRING_DATASOURCE_USERNAME: appvault
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      GCP_PROJECT_ID: YOUR_GCP_PROJECT_ID
    ports:
      - "8080:8080"

  nginx:
    image: nginx:alpine
    container_name: appvault-nginx
    restart: unless-stopped
    depends_on:
      - app
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
      - /var/www/certbot:/var/www/certbot:ro

volumes:
  postgres_data:
  redis_data:
EOF
```

Replace `YOUR_GITHUB_USERNAME` with your actual GitHub username and `YOUR_GCP_PROJECT_ID` with your project ID.

### main-vm: create nginx.conf

```bash
cat > /home/ubuntu/appvault/nginx.conf << 'EOF'
events {
    worker_connections 1024;
}

http {
    upstream springboot {
        server app:8080;
    }

    server {
        listen 80;
        server_name YOUR_DOMAIN.COM;

        location /.well-known/acme-challenge/ {
            root /var/www/certbot;
        }

        location / {
            return 301 https://$host$request_uri;
        }
    }

    server {
        listen 443 ssl;
        server_name YOUR_DOMAIN.COM;

        ssl_certificate /etc/letsencrypt/live/YOUR_DOMAIN.COM/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/YOUR_DOMAIN.COM/privkey.pem;

        client_max_body_size 500M;

        location / {
            proxy_pass http://springboot;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_read_timeout 300s;
            proxy_connect_timeout 300s;
        }
    }
}
EOF
```

Replace `YOUR_DOMAIN.COM` with your actual domain name.

### scanner-vm: create docker-compose.yml

SSH into scanner-vm:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_SCANNER_VM_IP
mkdir -p /home/ubuntu/scanner
```

```bash
cat > /home/ubuntu/scanner/docker-compose.yml << 'EOF'
version: "3.9"

services:
  redis:
    image: redis:7-alpine
    container_name: scanner-redis
    restart: unless-stopped
    volumes:
      - scanner_redis_data:/data
    ports:
      - "6380:6379"

  scanner:
    image: ghcr.io/YOUR_GITHUB_USERNAME/appvault-scanner:latest
    container_name: appvault-scanner
    restart: unless-stopped
    depends_on:
      - redis
    environment:
      REDIS_URL: redis://redis:6379
      GCP_PROJECT_ID: YOUR_GCP_PROJECT_ID
    ports:
      - "8000:8000"
    volumes:
      - /tmp/apk-scans:/tmp/apk-scans

  nginx:
    image: nginx:alpine
    container_name: scanner-nginx
    restart: unless-stopped
    depends_on:
      - scanner
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
      - /var/www/certbot:/var/www/certbot:ro

volumes:
  scanner_redis_data:
EOF
```

Create `/home/ubuntu/scanner/nginx.conf` with same pattern as main-vm but pointing to `scanner:8000` and using `YOUR_SCANNER_SUBDOMAIN.COM`.

---

## Step 13 — Get SSL certificates with Let's Encrypt

Do this on both VMs. Start with main-vm.

### On main-vm

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_MAIN_VM_IP

# Install certbot
sudo apt-get install -y certbot

# Point your domain A record to YOUR_MAIN_VM_IP first (do this in your DNS provider)
# Wait for DNS propagation (~5 min), then:

sudo certbot certonly --standalone \
  -d YOUR_DOMAIN.COM \
  --email YOUR_EMAIL@gmail.com \
  --agree-tos \
  --non-interactive

# Verify certificate created
sudo ls /etc/letsencrypt/live/YOUR_DOMAIN.COM/
```

You should see: `cert.pem`, `chain.pem`, `fullchain.pem`, `privkey.pem`

### Auto-renew cron for SSL

```bash
# Add to root's crontab
sudo crontab -e
```

Add this line:
```
0 3 * * * certbot renew --quiet && docker compose -f /home/ubuntu/appvault/docker-compose.yml restart nginx
```

### Repeat on scanner-vm

Same steps but with your scanner subdomain (e.g. `scanner.yourdomain.com`).

---

## Step 14 — Create the "hello world" Spring Boot app

On your local machine, in your GitHub repo:

```
appvault/
  backend/
    src/main/java/com/appvault/
      AppVaultApplication.java
      HealthController.java
    src/main/resources/
      application.yml
    Dockerfile
    pom.xml
```

### HealthController.java

```java
package com.appvault;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
            "status", "UP",
            "service", "appvault-backend",
            "version", "0.1.0"
        );
    }
}
```

### application.yml

```yaml
server:
  port: 8080

spring:
  application:
    name: appvault-backend
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/appvault}
    username: ${SPRING_DATASOURCE_USERNAME:appvault}
    password: ${DB_PASSWORD:changeme}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

gcp:
  project-id: ${GCP_PROJECT_ID}
```

### Dockerfile (backend)

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## Step 15 — Create GitHub Actions CI/CD pipeline

Create `.github/workflows/deploy-backend.yml` in your repo:

```yaml
name: Deploy backend to main-vm

on:
  push:
    branches: [main]
    paths:
      - 'backend/**'

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository_owner }}/appvault-backend

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build with Maven
        working-directory: ./backend
        run: mvn package -DskipTests

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: ./backend
          push: true
          tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest

      - name: Deploy to main-vm
        uses: appleboy/ssh-action@v1.0.0
        with:
          host: ${{ secrets.MAIN_VM_IP }}
          username: ubuntu
          key: ${{ secrets.SSH_PRIVATE_KEY }}
          script: |
            cd /home/ubuntu/appvault
            echo ${{ secrets.GITHUB_TOKEN }} | \
              docker login ghcr.io -u ${{ github.actor }} --password-stdin
            docker compose pull app
            docker compose up -d --no-deps app
            docker compose ps
```

Create `.github/workflows/deploy-scanner.yml`:

```yaml
name: Deploy scanner to scanner-vm

on:
  push:
    branches: [main]
    paths:
      - 'scanner/**'

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository_owner }}/appvault-scanner

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: ./scanner
          push: true
          tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest

      - name: Deploy to scanner-vm
        uses: appleboy/ssh-action@v1.0.0
        with:
          host: ${{ secrets.SCANNER_VM_IP }}
          username: ubuntu
          key: ${{ secrets.SSH_PRIVATE_KEY }}
          script: |
            cd /home/ubuntu/scanner
            echo ${{ secrets.GITHUB_TOKEN }} | \
              docker login ghcr.io -u ${{ github.actor }} --password-stdin
            docker compose pull scanner
            docker compose up -d --no-deps scanner
            docker compose ps
```

---

## Step 16 — Create the "hello world" FastAPI scanner

```
appvault/
  scanner/
    main.py
    requirements.txt
    Dockerfile
```

### main.py

```python
from fastapi import FastAPI
from datetime import datetime

app = FastAPI(title="AppVault Scanner", version="0.1.0")

@app.get("/health")
def health():
    return {
        "status": "UP",
        "service": "appvault-scanner",
        "version": "0.1.0",
        "timestamp": datetime.utcnow().isoformat()
    }
```

### requirements.txt

```
fastapi==0.111.0
uvicorn[standard]==0.30.0
```

### Dockerfile (scanner)

```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

---

## Step 17 — First deployment

Commit and push everything:

```bash
git add .
git commit -m "chore: initial infrastructure setup"
git push origin main
```

Watch GitHub Actions run:

1. Go to your repo → Actions tab
2. You should see both workflows trigger
3. Both should complete with green checkmarks in ~3-5 minutes

### Verify both health endpoints

```bash
# Main VM
curl https://YOUR_DOMAIN.COM/health
# Expected: {"status":"UP","service":"appvault-backend","version":"0.1.0"}

# Scanner VM
curl https://YOUR_SCANNER_SUBDOMAIN.COM/health
# Expected: {"status":"UP","service":"appvault-scanner","version":"0.1.0"}
```

---

## Step 18 — Set up PostgreSQL backup cron

SSH into main-vm:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_MAIN_VM_IP
```

Create the backup script:

```bash
sudo mkdir -p /opt/appvault/scripts
sudo tee /opt/appvault/scripts/backup-postgres.sh << 'SCRIPT'
#!/bin/bash
set -e

TIMESTAMP=$(date +%Y-%m-%d)
BACKUP_FILE="/tmp/db_backup_${TIMESTAMP}.sql.gz"
GCS_PATH="gs://appvault-backups/postgres/${TIMESTAMP}/db.sql.gz"
LOG_FILE="/var/log/appvault-backup.log"

echo "[$(date)] Starting backup..." >> "${LOG_FILE}"

# Get DB password from Secret Manager
DB_PASSWORD=$(gcloud secrets versions access latest --secret="db-password" 2>/dev/null)

if [ -z "${DB_PASSWORD}" ]; then
  echo "[$(date)] ERROR: Could not retrieve DB password from Secret Manager" >> "${LOG_FILE}"
  exit 1
fi

# Run pg_dump inside the postgres container
docker exec appvault-postgres pg_dump \
  -U appvault \
  -d appvault \
  -F c \
  | gzip > "${BACKUP_FILE}"

# Upload to GCS
gcloud storage cp "${BACKUP_FILE}" "${GCS_PATH}"

# Clean up local file
rm -f "${BACKUP_FILE}"

echo "[$(date)] Backup completed: ${GCS_PATH}" >> "${LOG_FILE}"
SCRIPT

sudo chmod +x /opt/appvault/scripts/backup-postgres.sh
```

### Install gcloud CLI on main-vm

```bash
curl https://sdk.cloud.google.com | bash
exec -l $SHELL
gcloud version
```

### Authenticate gcloud on main-vm using service account

```bash
# Retrieve the service account key from Secret Manager
gcloud secrets versions access latest \
  --secret="gcs-main-service-account" > /tmp/main-sa-key.json

# Activate the service account
gcloud auth activate-service-account \
  --key-file=/tmp/main-sa-key.json

# Clean up the key file
rm /tmp/main-sa-key.json

# Verify authentication
gcloud auth list
```

### Add backup cron

```bash
sudo crontab -e
```

Add this line (runs at 2:00 AM every night):

```
0 2 * * * /opt/appvault/scripts/backup-postgres.sh >> /var/log/appvault-backup.log 2>&1
```

### Test the backup script manually

```bash
sudo /opt/appvault/scripts/backup-postgres.sh

# Check it ran
cat /var/log/appvault-backup.log

# Verify file appeared in GCS
gcloud storage ls gs://appvault-backups/postgres/
```

---

## Step 19 — Start PostgreSQL and Redis

On main-vm, bring up the database services:

```bash
cd /home/ubuntu/appvault

# Start only postgres and redis first
docker compose up -d postgres redis

# Wait for them to become healthy
docker compose ps

# Verify postgres is accepting connections
docker exec appvault-postgres pg_isready -U appvault
# Expected: /var/run/postgresql:5432 - accepting connections

# Verify Redis responds
docker exec appvault-redis redis-cli ping
# Expected: PONG
```

---

## Step 20 — Set up DB password for PostgreSQL container

The Docker Compose file references a secret file for the password. Set it up:

```bash
# On main-vm
DB_PASSWORD=$(gcloud secrets versions access latest --secret="db-password")

# Create Docker secret directory
sudo mkdir -p /run/secrets
echo -n "${DB_PASSWORD}" | sudo tee /run/secrets/db_password > /dev/null
sudo chmod 600 /run/secrets/db_password
```

Restart postgres container to pick up the password:

```bash
cd /home/ubuntu/appvault
docker compose up -d postgres
docker compose ps
```

---

## Step 21 — Verify the complete setup

Run this checklist. Every item should pass before moving to Sprint 2.

```bash
# On your LOCAL machine:

# 1. Main VM health endpoint
curl -s https://YOUR_DOMAIN.COM/health | python3 -m json.tool

# 2. Scanner VM health endpoint
curl -s https://YOUR_SCANNER_SUBDOMAIN.COM/health | python3 -m json.tool

# 3. GCS buckets exist and are private
gcloud storage buckets describe gs://appvault-files
gcloud storage buckets describe gs://appvault-backups

# 4. All secrets exist in Secret Manager
gcloud secrets list --format="value(name)" | sort

# 5. Backup file exists in GCS (after manually running backup script)
gcloud storage ls "gs://appvault-backups/postgres/"

# On MAIN-VM:
# 6. PostgreSQL healthy
docker exec appvault-postgres pg_isready -U appvault

# 7. Redis healthy
docker exec appvault-redis redis-cli ping

# 8. Docker containers running
docker compose -f /home/ubuntu/appvault/docker-compose.yml ps
```

Expected results:
- Items 1 and 2: JSON with `"status": "UP"`
- Items 3 and 4: No errors
- Item 5: At least one `.sql.gz` file listed
- Item 6: `accepting connections`
- Item 7: `PONG`
- Item 8: All services `Up` or `healthy`

---

## Troubleshooting

### "Permission denied" on SSH
```bash
chmod 600 ~/.ssh/appvault_deploy
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_VM_IP
```

### GitHub Actions fails at "Deploy to VM" step
- Confirm `SSH_PRIVATE_KEY` secret contains the full private key text including header/footer lines
- Confirm `MAIN_VM_IP` secret has no extra spaces or newlines

### Docker Compose: "Cannot connect to Docker daemon"
```bash
# On the VM
sudo systemctl status docker
sudo systemctl start docker
# If still failing:
sudo usermod -aG docker ubuntu
newgrp docker
```

### Let's Encrypt fails: "Could not bind to port 80"
Nginx or another process is using port 80. Stop it first:
```bash
sudo systemctl stop nginx    # if system nginx is running
sudo certbot certonly --standalone -d YOUR_DOMAIN.COM ...
```

### Backup script fails: "gcloud: command not found"
```bash
# On main-vm
echo $PATH
# If gcloud is missing, re-run:
source ~/.bashrc
gcloud version
```

### PostgreSQL container won't start
```bash
docker compose logs postgres
# Usually a password mismatch or volume permission issue
# Nuclear option to reset data (ONLY in dev):
docker compose down -v
docker compose up -d postgres
```

---

## What you have at the end of Sprint 1

| Component | Status | Location |
|---|---|---|
| main-vm (e2-medium) | Running | GCP asia-south1-a |
| scanner-vm (e2-small) | Running | GCP asia-south1-a |
| PostgreSQL 15 | Running on main-vm | Port 5432 (internal) |
| Redis 7 | Running on main-vm | Port 6379 (internal) |
| Spring Boot (hello world) | Deployed via CI/CD | https://yourdomain.com |
| FastAPI scanner (hello world) | Deployed via CI/CD | https://scanner.yourdomain.com |
| GCS appvault-files | Created, private | gs://appvault-files |
| GCS appvault-backups | Created, with lifecycle | gs://appvault-backups |
| Secret Manager | 6 secrets stored | GCP Secret Manager |
| GitHub Actions | 2 pipelines active | Deploys on push to main |
| pg_dump cron | Runs nightly 2AM | Uploads to GCS |
| SSL certificates | Active on both VMs | Let's Encrypt |

Sprint 2 starts here: building the Auth module on top of this foundation.