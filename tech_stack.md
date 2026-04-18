# Tech Stack — AppVault

## Guiding principles

- Every tool must have a clear owner: either a layer of the app or a specific problem it solves
- No paid services until GCP credits run out (~13 months at estimated burn rate)
- All tools are open source or have generous free tiers without requiring a credit card
- Stack maps directly to the developer's learning roadmap: Angular + Spring Boot + Flutter

---

## Full stack overview

| Layer | Technology | Why |
|---|---|---|
| Web frontend | Angular 17 + TypeScript | Matches dev's learning roadmap; one codebase for all three web surfaces |
| Mobile app | Flutter (Dart) | Developer's strongest skill; handles download + Android install flow |
| Main backend | Spring Boot (Java) | Matches learning roadmap; mature for file handling, security, async jobs |
| APK scanner | Python + FastAPI | MobSF, AndroGuard, APKTool are all Python-native; isolated microservice |
| Database | PostgreSQL (self-hosted on GCP VM) | Full-text search built in; no 90-day deletion like Render free tier |
| Cache + queue broker | Redis (self-hosted on GCP VM) | Token blacklisting, Bucket4j rate limiting, webhook replay protection |
| File storage | Google Cloud Storage | Covered by GCP credits; no 10GB cap like Cloudflare R2 |
| CDN | Cloudflare (free tier) | No egress fees; pointed at GCS for APK delivery |
| Email | Resend (free tier) | 3,000 emails/month free; clean Java SDK; no credit card required |
| Secrets | GCP Secret Manager | Free up to 6 active secret versions; eliminates .env risk entirely |
| Rate limiting | Bucket4j + Redis | Open source; distributed token bucket across Spring Boot instances |
| APK static analysis | MobSF (self-hosted) | Full static analysis: permissions, certs, manifest, malware heuristics |
| APK parsing | AndroGuard + APKTool | Extracts packageName, certHash, versionCode, minSdk, permissions |
| Search | PostgreSQL FTS → Meilisearch | Start with built-in FTS; migrate to Meilisearch when relevance matters |
| Auth | Spring Security + custom JWT | Access token + refresh token; USER / DEVELOPER / ADMIN roles |
| Background jobs | Spring @Async + Celery (scanner) | Main platform: Spring @Async; scanner: Python Celery + Redis |
| CI/CD | GitHub Actions | Free 2,000 min/month; SSH deploy to GCP VMs |
| Backup | pg_dump cron → GCS | Nightly compressed dump; GCS lifecycle for 30-day retention |

---

## Frontend: Angular monorepo

One Angular app with three lazy-loaded modules. No three separate deployments.

```
src/
  app/
    store/          ← user-facing store (lazy)
    console/        ← developer console (lazy)
    admin/          ← admin panel (lazy)
    shared/         ← auth interceptors, services, guards
```

**Libraries used:**
- Angular Material — UI components
- Reactive Forms — APK submission form, app listing editor
- Angular Router — role-based routing with guards
- Axios / HttpClient — API calls with JWT interceptor

**Route guards:**
- `AuthGuard` — requires any valid token
- `DeveloperGuard` — requires DEVELOPER role
- `AdminGuard` — requires ADMIN role

---

## Mobile app: Flutter

Scope for v1:

- Auth (login, register, email verification)
- Browse store (home feed, categories, featured)
- Search + filter (category, content rating, version)
- App detail page (description, screenshots, permissions, changelog)
- Download APK — triggers Android native package installer
- User library (download history, version awareness)
- In-app notifications

**Key packages:**
- `dio` — HTTP client with interceptors (token attach + refresh)
- `riverpod` — state management
- `open_file` or `install_plugin` — triggers Android package installer
- `permission_handler` — handles `REQUEST_INSTALL_PACKAGES`
- `cached_network_image` — screenshot and icon loading

**Install flow state machine:**

```
IDLE → PERMISSION_CHECK → REQUESTING_PERMISSION → DOWNLOADING → READY_TO_INSTALL → INSTALLING → DONE
                                  ↓                                                               ↓
                           PERMISSION_DENIED                                                  FAILED
```

---

## Main backend: Spring Boot

**Key dependencies:**
- `spring-boot-starter-web` — REST API
- `spring-boot-starter-security` — JWT auth + role-based access
- `spring-boot-starter-data-jpa` — PostgreSQL via Hibernate
- `spring-boot-starter-validation` — request validation
- `google-cloud-storage` — GCS signed URL generation + upload
- `bucket4j-redis` — distributed rate limiting
- `resend-java` — email sending (async)
- `google-cloud-secretmanager` — secret retrieval at startup

**Key patterns:**
- `@Async` methods for email sending and analytics event writes — never blocks request thread
- `ContentCachingRequestWrapper` on scanner webhook endpoint — reads raw body bytes for HMAC before JSON parsing
- `MessageDigest.isEqual()` for HMAC signature comparison — constant-time, no timing leaks
- Short-lived GCS signed URLs (5–10 minute TTL) generated per download request
- Spring `@Scheduled` cron for nightly pg_dump trigger

---

## Scanner microservice: Python + FastAPI

Deployed on a separate GCP VM. Completely isolated from main platform.

**Flow:**
1. Main platform sends `POST /scan` with APK GCS URL + appId + HMAC signature
2. Scanner validates HMAC, downloads APK from GCS
3. Runs static analysis: MobSF + AndroGuard + APKTool
4. Returns JSON risk report via webhook back to main platform

**What the risk report contains:**
```json
{
  "appId": "uuid",
  "callbackId": "uuid",
  "scanStatus": "PASSED | FLAGGED | REJECTED",
  "riskScore": 0-100,
  "certFingerprint": "sha256:...",
  "packageName": "com.example.app",
  "permissions": ["android.permission.CAMERA", "..."],
  "flags": ["DYNAMIC_CODE_LOADING", "REFLECTION_USAGE"],
  "minSdk": 21,
  "targetSdk": 34
}
```

**Libraries:**
- `fastapi` — lightweight async HTTP framework
- `androguard` — APK static analysis, certificate extraction
- `apktool` (via subprocess) — decompile and inspect manifest
- `celery` + `redis` — queue scan jobs if multiple arrive simultaneously
- `httpx` — async HTTP client for webhook callback

---

## Infrastructure: GCP free tier

### GCP credits available
₹28,444 (~$340 USD) — estimated duration: 12–14 months at projected burn rate.

| Resource | Spec | Monthly cost (est.) |
|---|---|---|
| e2-medium VM (main platform) | 2 vCPU, 4GB RAM | ~₹1,200 |
| e2-small VM (scanner) | 2 vCPU, 2GB RAM | ~₹600 |
| Google Cloud Storage (50GB) | APKs, screenshots, backups | ~₹85 |
| Cloud networking / egress | Minimal (Cloudflare handles CDN) | ~₹200 |
| **Total** | | **~₹2,085/month** |

Credits last approximately **13 months** at this rate.

### VM layout

```
GCP e2-medium (main-vm)
  ├── Spring Boot app (port 8080)
  ├── PostgreSQL (port 5432)
  ├── Redis (port 6379)
  └── Meilisearch (port 7700, v2)

GCP e2-small (scanner-vm)
  ├── FastAPI scanner (port 8000)
  ├── MobSF (port 8008)
  └── Celery worker
```

### GCS bucket structure

```
gs://appvault-files/
  apks/{appId}/{versionId}/app.apk
  screenshots/{appId}/{filename}
  icons/{appId}/icon.png
  banners/{appId}/banner.png

gs://appvault-backups/
  postgres/{YYYY-MM-DD}/db.sql.gz
```

### GCP Secret Manager secrets

| Secret name | Value stored |
|---|---|
| `db-password` | PostgreSQL password |
| `jwt-secret` | JWT signing key |
| `gcs-service-account` | GCS service account JSON |
| `resend-api-key` | Resend email API key |
| `scanner-hmac-secret` | Shared HMAC secret between main + scanner |
| `scanner-api-key` | API key for scanner endpoint auth |

---

## External services (all free, no credit card)

| Service | Free tier | Used for |
|---|---|---|
| Cloudflare | Unlimited CDN | APK and asset delivery |
| Resend | 3,000 emails/month | Approval, rejection, password reset emails |
| GitHub Actions | 2,000 min/month | CI/CD deploy to both GCP VMs |
| GCP Secret Manager | 6 free secret versions | All runtime secrets |

---

## What is NOT in the stack (intentionally)

| Excluded | Reason |
|---|---|
| Render / Railway | Replaced by GCP credits (better specs, no spin-down) |
| Cloudflare R2 | Replaced by GCS (covered by credits, no 10GB limit) |
| Firebase | No need; PostgreSQL + custom JWT handles auth |
| Kafka / RabbitMQ | Spring @Async + Celery is sufficient for v1 volume |
| Kubernetes | Overkill for two VMs; Docker Compose is enough |
| AAB processing | Requires Android SDK on scanner VM; v2 only |
| Crash reporting SDK | Separate product in itself; v2 only |

---

## Deployment stack per VM

Both VMs use Docker Compose for service orchestration.

**main-vm `docker-compose.yml` services:**
- `app` — Spring Boot JAR (built by GitHub Actions)
- `postgres` — PostgreSQL 15 with persistent volume
- `redis` — Redis 7 with persistent volume
- `nginx` — reverse proxy (Spring Boot on port 80/443)

**scanner-vm `docker-compose.yml` services:**
- `scanner` — FastAPI app
- `mobsf` — MobSF static analysis
- `celery` — Celery worker for scan queue
- `nginx` — reverse proxy (FastAPI on port 80/443)

GitHub Actions deploys via SSH: builds Docker image, pushes to GitHub Container Registry, SSH into VM, pulls image, runs `docker compose up -d`.