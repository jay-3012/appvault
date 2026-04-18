# Sprint Planning — AppVault

## How to read this document

Each sprint is 2 weeks. Every task has a clear acceptance criterion — "done" means the criterion passes, not just that code was written. Tasks are sized as S (half day), M (1 day), L (2 days), XL (3+ days).

Sprints map directly to roadmap phases. Security-critical work (secrets, HMAC, signed URLs) is never moved to a later sprint.

---

## Sprint 1 — Infrastructure and foundation
**Weeks 1–2 | Phase 0**

Goal: Both VMs running, CI/CD deploying, secrets managed, DB schema live, backup running.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 1.1 | Provision GCP e2-medium VM (main-vm) with Docker Compose | M | `docker ps` shows healthy containers; SSH access via GitHub Actions key |
| 1.2 | Provision GCP e2-small VM (scanner-vm) with Docker Compose | M | Same as above for scanner-vm |
| 1.3 | Set up GCP Secret Manager; populate all 6 secrets | S | Spring Boot logs "secrets loaded" at startup without reading values |
| 1.4 | Create GCS buckets: `appvault-files` and `appvault-backups` | S | Both buckets exist; `appvault-files` has no public access (IAM check) |
| 1.5 | GitHub Actions pipeline: build Spring Boot → deploy to main-vm | L | Push to `main` branch → Spring Boot `GET /health` returns 200 on main-vm within 5 min |
| 1.6 | GitHub Actions pipeline: build FastAPI → deploy to scanner-vm | L | Push to `main` branch → FastAPI `GET /health` returns 200 on scanner-vm within 5 min |
| 1.7 | Write initial DB migration (Flyway): users, developer_profiles, apps, app_versions, app_ownership, notifications, download_events, audit_logs tables | L | `flyway migrate` runs clean with zero errors; all tables exist in PostgreSQL |
| 1.8 | Set up pg_dump cron job on main-vm → compress → upload to GCS backup bucket | M | Backup file `postgres/YYYY-MM-DD/db.sql.gz` appears in GCS the morning after setup |
| 1.9 | Point Cloudflare DNS at main-vm; configure Nginx reverse proxy | M | `https://yourdomain.com/health` returns 200 |
| 1.10 | Set up Nginx on scanner-vm with HTTPS | S | Scanner health endpoint accessible over HTTPS |

**Sprint 1 definition of done:** Both VMs healthy, CI/CD green, secrets loaded, DB schema migrated, first backup in GCS.

---

## Sprint 2 — Auth module
**Weeks 3–4 | Phase 1**

Goal: Complete JWT auth system with three roles, email verification, token refresh, and logout.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 2.1 | Spring Security config: JWT filter, role-based guards (USER / DEVELOPER / ADMIN) | L | Unauthenticated request to protected route → 401; wrong role → 403 |
| 2.2 | `POST /auth/register` endpoint with email verification token generation | M | New user created with `status: PENDING_VERIFICATION`; verification email sent async |
| 2.3 | Resend email integration (`@Async`) for verification + password reset emails | M | Resend API call logs success; request thread is not blocked |
| 2.4 | `POST /auth/verify-email?token=` endpoint | S | Token valid → user status becomes `ACTIVE`; expired token → 400 |
| 2.5 | `POST /auth/login` — returns access token (15 min JWT) + refresh token (7 days, stored in DB) | M | Valid credentials → tokens returned; invalid credentials → 401 |
| 2.6 | `POST /auth/refresh` — validates refresh token, issues new access + refresh token (rotation) | M | Valid refresh token → new tokens issued; old refresh token invalidated in DB |
| 2.7 | `POST /auth/logout` — adds access token to Redis blacklist | S | After logout, same access token returns 401 |
| 2.8 | Developer registration flow: user promotes to DEVELOPER role by accepting developer agreement | S | User with DEVELOPER role can access `/developer/*` endpoints |
| 2.9 | Unit + integration tests for all auth endpoints | L | All auth tests green; 401/403 cases covered; refresh rotation tested |

**Sprint 2 definition of done:** Auth endpoints all passing tests; roles enforced; token blacklist working; email sends without blocking request.

---

## Sprint 3 — APK parser module
**Weeks 5 | Phase 2**

> This is a 1-week sprint. APK parser is a contained module — keep it tight.

Goal: Reliable Python parser that extracts all required metadata from any APK.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 3.1 | Set up Python project structure: `apk_parser/` with AndroGuard + APKTool dependencies | S | `pip install` runs clean; `python -c "import androguard"` succeeds |
| 3.2 | `parse_apk(path)` function: extracts packageName, versionCode, versionName, minSdk, targetSdk | M | Returns correct values for 5 test APKs; raises `ParseError` for corrupt file |
| 3.3 | Certificate fingerprint extraction (SHA-256 of signing cert) | M | Same APK always returns identical fingerprint; different-signed APK returns different value |
| 3.4 | Permission extraction from AndroidManifest | S | Returns complete list of `uses-permission` entries |
| 3.5 | Icon extraction (base64 PNG) | S | Icon returned for standard APKs; `None` returned gracefully if missing |
| 3.6 | Version code validation: new versionCode must be higher than existing | S | `validate_version_code(new, existing)` raises `ValidationError` when new ≤ existing |
| 3.7 | Unit tests: 5 real APK fixtures covering edge cases (no icon, many permissions, older SDK) | L | All tests green; edge cases handled without crashes |
| 3.8 | FastAPI `POST /parse` endpoint wrapping the parser | S | POST with GCS APK URL → returns parsed metadata JSON within 10 seconds |

**Sprint 3 definition of done:** Parser returns correct, stable metadata for all test APKs; FastAPI endpoint live on scanner-vm.

---

## Sprint 4 — Scanner microservice
**Weeks 6–7 | Phase 3**

Goal: Scanner VM receives APK URL, analyzes it, sends signed risk report back via webhook. Tested standalone.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 4.1 | HMAC-SHA256 signing utility: `sign_payload(payload, secret)` and `verify_signature(payload, sig, secret)` | M | Correct signature verifies; tampered payload fails |
| 4.2 | `POST /scan` endpoint: validates inbound HMAC from main platform | M | Invalid HMAC on inbound request → 401; valid → accepted |
| 4.3 | APK download from GCS URL to temp file | S | APK downloaded to `/tmp/`; cleaned up after scan |
| 4.4 | MobSF integration: run static analysis, extract risk score and flags | XL | `DYNAMIC_CODE_LOADING` flag detected in known test APK; returns score 0–100 |
| 4.5 | Assemble risk report JSON: scanStatus, riskScore, certFingerprint, permissions, flags, minSdk | M | Report matches schema from project description; all fields populated |
| 4.6 | Outbound webhook: sign report + POST to main platform callback URL | M | Main platform mock endpoint receives webhook; HMAC on outbound webhook is valid |
| 4.7 | Celery task queue for concurrent scan jobs | M | Two simultaneous scan requests both complete without interference |
| 4.8 | Redis replay protection: `callbackId` stored in Redis with 24h TTL | S | Second request with same `callbackId` returns 200 immediately, no second processing |
| 4.9 | Spring Boot webhook receiver: `POST /internal/scanner-callback` with HMAC verification + `MessageDigest.isEqual` | L | Fake signature → 401; valid signature → 200; duplicate callbackId → 200 (idempotent) |
| 4.10 | Integration test: full scan round-trip using a real APK file | L | Submit APK → scanner processes → callback received → app version status updated in DB |

**Sprint 4 definition of done:** Full scan round-trip working end-to-end; HMAC verified in both directions; replay protection active.

---

## Sprint 5 — File storage + app submission API
**Weeks 8–10 | Phases 4 + 5**

Goal: Developer can upload an APK, trigger parsing and scanning, and track submission status.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 5.1 | `StorageService`: upload APK to GCS at `apks/{appId}/{versionId}/app.apk` | M | File appears in GCS at correct path; no public access on bucket |
| 5.2 | `StorageService`: upload screenshot + icon + banner images | S | Images stored at correct GCS paths |
| 5.3 | `StorageService`: `generateSignedDownloadUrl(gcsPath, ttlMinutes)` | M | URL works during TTL; 403 after expiry; direct bucket URL → 403 always |
| 5.4 | `POST /developer/apps` — create app listing with metadata + content rating | M | App created with status `DRAFT`; content rating is required field (400 if missing) |
| 5.5 | `POST /developer/apps/{appId}/versions` — APK upload + parse + ownership check | XL | APK stored; parser called; ownership check runs; version status = `PENDING_SCAN` |
| 5.6 | Ownership table write on first submission; conflict detection on subsequent | M | Same packageName from different developer → 409; same developer → allowed if certHash matches |
| 5.7 | Async dispatch to scanner: signed `POST /scan` to scanner-vm | M | Scanner-vm receives signed request within 2 seconds of submission |
| 5.8 | Scanner callback updates version status to `SCAN_COMPLETE` | S | After callback, `GET /developer/apps/{appId}/versions` shows `SCAN_COMPLETE` |
| 5.9 | `GET /developer/apps` and `GET /developer/apps/{appId}/versions` | S | Returns correct data for authenticated developer; returns 403 for other developer's apps |
| 5.10 | `PATCH /developer/apps/{appId}` — update listing metadata | S | Title, description, screenshots updatable; packageName not updatable after first submission |
| 5.11 | Integration tests: full submission flow | L | Submit APK → scan dispatched → callback → status updated; all ownership conflict cases tested |

**Sprint 5 definition of done:** Developer can submit an app, trigger scanning, and see status update. Ownership enforcement and signed URL both tested.

---

## Sprint 6 — Admin review + release tracks
**Weeks 11–12 | Phases 6 + 7**

Goal: Admin can review and act on submissions. Approved apps enter release track system.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 6.1 | `GET /admin/review-queue` — paginated, shows scan result + metadata + content rating | M | Only ADMIN token can access; returns apps with `SCAN_COMPLETE` status |
| 6.2 | `POST /admin/apps/{appId}/versions/{versionId}/approve` — moves version to ALPHA | M | Version status = `APPROVED`; track = `ALPHA`; `isActive = true`; audit log entry written |
| 6.3 | `POST /admin/apps/{appId}/versions/{versionId}/reject` — requires reason enum | M | Missing reason enum → 400; valid rejection → status = `REJECTED`; audit log entry written |
| 6.4 | `PATCH /admin/apps/{appId}/content-rating` — override developer's rating | S | Rating updated; audit log entry written |
| 6.5 | `POST /admin/apps/{appId}/suspend` and `POST /admin/apps/{appId}/remove` | M | Suspended app hidden from store; removed app + packageName blocked; audit log for each |
| 6.6 | Audit log: every admin action persisted with adminId, action, targetId, timestamp, notes | S | After approve → audit row exists; after reject → audit row exists |
| 6.7 | Release track model: `AppVersion` gets `track` (ALPHA/BETA/PROD) + `isActive` fields | M | DB migration adds columns; uniqueness constraint on (appId, track, isActive=true) |
| 6.8 | `POST /developer/apps/{appId}/versions/{versionId}/promote` — Alpha → Beta → Prod only | M | Alpha → Production skipping Beta → 422; valid promotion → track updated; notification fired |
| 6.9 | `POST /developer/apps/{appId}/versions/{versionId}/rollback` — reactivate older version | M | Older version `isActive = true`; current `isActive = false`; DB constraint holds |
| 6.10 | Tester management: add/remove tester emails for Alpha/Beta access | M | Alpha build only downloadable by listed tester emails; non-tester → 403 |
| 6.11 | Integration tests: approval → promotion → rollback flow | L | Full lifecycle tested; DB constraints verified; audit log complete |

**Sprint 6 definition of done:** Admin can approve and reject; release tracks enforce sequential promotion; rollback works; audit log complete.

---

## Sprint 7 — Download flow + user store API
**Weeks 13–14 | Phases 8 + 9**

Goal: Users can browse, search, and download apps. All downloads are tracked and served via signed URLs.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 7.1 | `GET /apps/{appId}/download` — validates JWT, confirms PRODUCTION+active, logs event, generates signed URL, returns 302 | L | No JWT → 401; non-production app → 403; valid → 302 to signed URL; download_event row written |
| 7.2 | Download event table: `download_events(id, userId, appId, versionId, ipAddress, createdAt)` | S | Row written on every successful download; userId nullable for anonymous tracking by IP |
| 7.3 | GCS bucket policy confirmed: no public object access | S | `curl https://storage.googleapis.com/appvault-files/apks/...` → 403 |
| 7.4 | `GET /apps` — browse with filters: category, contentRating, sort | M | Filter by `contentRating=EVERYONE` returns zero MATURE apps; sort by `mostDownloaded` is correct |
| 7.5 | `GET /apps/search?q=` — PostgreSQL full-text search on title + description | M | Exact title match returns app; partial match works; empty query returns all apps |
| 7.6 | `GET /apps/{appId}` — full detail: description, screenshots, permissions, changelog, ratings summary | M | Returns only PRODUCTION active version data; permissions list from parsed APK |
| 7.7 | `GET /apps/featured` — admin-curated collection with Redis cache (5-min TTL) | S | Second call within 5 min returns cached response (check via response time drop) |
| 7.8 | `GET /categories` — list all categories | S | Returns static category list |
| 7.9 | Developer analytics: download counts per day/week/month from download_events | M | `GET /developer/apps/{appId}/analytics` returns correct aggregated counts |
| 7.10 | Integration tests: download flow, search, browse | L | All download security cases tested; search returns correct results |

**Sprint 7 definition of done:** Signed URL download working; direct GCS access blocked; browse and search functional; download events logged.

---

## Sprint 8 — Notifications + ratings
**Weeks 15–16 | Phases 10 + 11**

Goal: Event-driven notifications for all lifecycle events. Verified-download review system.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 8.1 | Notification event publisher: Spring `ApplicationEventPublisher` for all key events | M | Publishing event does not block calling thread; verified via thread name in logs |
| 8.2 | In-app notification table + event listener: writes notification row on each event | M | App approved → notification row written for developer within 1 second |
| 8.3 | Resend email templates: approval, rejection, new version in production, password reset | L | Each email sends with correct subject and body; test mode confirmed |
| 8.4 | `GET /notifications` — list unread for authenticated user | S | Only current user's notifications returned; other users' notifications not visible |
| 8.5 | `PATCH /notifications/{id}/read` and `PATCH /notifications/read-all` | S | `isRead` updated; GET no longer returns marked notifications in unread filter |
| 8.6 | Retry logic on Resend API: 3 attempts with exponential backoff | M | Simulate Resend 500 → retried 3 times; failure logged; no exception propagated to caller |
| 8.7 | `POST /apps/{appId}/reviews` — requires verified download; validates one review per user per app | M | No download record → 403; second review from same user → 409; valid review → 201 |
| 8.8 | `GET /apps/{appId}/reviews` — paginated with rating breakdown (1★–5★ distribution) | M | Returns reviews in descending date order; breakdown sums to total count |
| 8.9 | Rating aggregate update on app record after new review | S | Average and count on app record match manual calculation after 5 reviews |
| 8.10 | `PATCH /admin/reviews/{reviewId}/moderate` — hide/show review | S | Hidden review not returned in public GET; admin can unhide; audit log entry written |

**Sprint 8 definition of done:** Notifications fire async on all key events; emails send without blocking; reviews require verified download; moderation working.

---

## Sprint 9 — Angular frontend (auth + developer console)
**Weeks 17–18 | Phase 12, part 1**

Goal: Web auth flow and complete developer console.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 9.1 | Angular project setup: monorepo with store / console / admin modules + shared | M | `ng serve` starts; routes load correct lazy modules |
| 9.2 | Auth pages: login, register, email verification | M | Login with valid credentials → navigates to correct dashboard per role |
| 9.3 | JWT interceptor: attaches token to all API requests; handles 401 → refresh → retry | L | Token expires mid-session → refresh happens transparently; logout on refresh failure |
| 9.4 | Developer console layout: sidebar navigation, app list page | M | Developer sees own apps; other developers' apps not visible |
| 9.5 | App submission form: metadata fields + APK upload + screenshot upload + content rating | XL | Form validates all required fields; APK upload shows progress; submission creates version |
| 9.6 | Version management page: tracks, status, promote button | M | Alpha → Beta → Prod promotion works from UI; rollback button available |
| 9.7 | Analytics dashboard: download chart (Chart.js), rating breakdown | L | Chart renders correctly for 30-day window; empty state shown for new apps |
| 9.8 | Notification bell in header: shows unread count, marks as read on click | M | Count updates without page reload; clicking notification navigates to relevant page |

**Sprint 9 definition of done:** Developer can register, submit app, manage versions, and view analytics from web browser.

---

## Sprint 10 — Angular frontend (user store + admin panel)
**Weeks 19–20 | Phase 12, part 2**

Goal: Public user store and admin panel.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 10.1 | User store home: featured apps + categories grid | M | Featured collection loads; clicking category filters apps |
| 10.2 | Search page: input with live results, filter sidebar | L | Search returns results as user types (debounced 300ms); filters narrow results correctly |
| 10.3 | App detail page: screenshots carousel, permissions list, changelog, reviews | L | Screenshots swipeable; permissions readable; reviews paginated |
| 10.4 | Download button: calls download endpoint, handles 302 redirect | M | Clicking download in browser initiates file download via signed URL |
| 10.5 | Review submission form: star rating + text; shown only to users who downloaded the app | M | Submit review form appears only after download; submit sends to API |
| 10.6 | Admin panel: review queue table with approve/reject actions | L | Admin sees pending submissions; approve/reject calls correct endpoints; queue updates |
| 10.7 | Admin: content rating override, app suspend/remove with confirmation dialog | M | Override updates rating on app detail page; suspend hides app from store |
| 10.8 | Admin: platform analytics page (total downloads, submission counts, rejection reasons chart) | L | Charts render with real data; date range filter works |

**Sprint 10 definition of done:** Full user store browsable; admin can complete full review cycle from web browser.

---

## Sprint 11 — Flutter mobile app (auth + store)
**Weeks 21–22 | Phase 13, part 1**

Goal: Flutter app with auth, home feed, and app detail page.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 11.1 | Flutter project setup with Riverpod, Dio, bottom navigation | S | App runs on device; bottom nav switches between tabs |
| 11.2 | Dio HTTP client with JWT interceptor (same pattern as Angular) | M | Token attaches to requests; refresh works transparently on 401 |
| 11.3 | Login + register screens | M | Login with valid credentials → lands on home feed |
| 11.4 | Email verification screen + flow | S | Verification link deeplink opens app and verifies account |
| 11.5 | Home feed: featured apps + category tabs | M | Featured apps load; category tabs filter results |
| 11.6 | Search screen: text input + filter options | M | Search returns results; filter by content rating works |
| 11.7 | App detail screen: description, screenshots, permissions, changelog, reviews | L | Screenshots horizontal scroll; permissions collapsed/expanded; reviews paginated |
| 11.8 | In-app notification feed | M | Notification bell shows unread count; tapping opens notification list |

**Sprint 11 definition of done:** App browsable end-to-end on a real Android device; auth and navigation fully functional.

---

## Sprint 12 — Flutter mobile app (download + install + library)
**Weeks 23–24 | Phase 13, part 2**

Goal: Complete APK download and install flow. User library and version awareness.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 12.1 | `REQUEST_INSTALL_PACKAGES` permission declaration in AndroidManifest | S | Permission declared; tested on Android 8, 10, 13 |
| 12.2 | Permission check before download: prompt user if not granted | M | If permission denied → show explanation dialog; if granted → proceed to download |
| 12.3 | Download state machine: IDLE → PERMISSION_CHECK → DOWNLOADING → READY → INSTALLING → DONE | XL | All state transitions tested; each state shows correct UI feedback |
| 12.4 | Download progress indicator: linear progress bar with percentage | M | Progress bar visible during download; matches actual file progress |
| 12.5 | `open_file` or `install_plugin` to trigger Android native installer | M | After download completes, Android system installer dialog appears |
| 12.6 | Test on real devices: Android 8 (API 26), Android 10 (API 29), Android 13 (API 33) | L | Install works on all three Android versions; permission flow correct per version |
| 12.7 | Download failure handling: network error, storage full, permission revoked | M | Each error state shows descriptive message; retry available |
| 12.8 | User library screen: list of downloaded apps with version and date | M | All downloaded apps appear; apps with newer version show "Update available" badge |
| 12.9 | Review submission from app detail (post-download) | S | Review form appears after confirmed download; submitted review appears in list |

**Sprint 12 definition of done:** APK download and install working on real devices across Android 8/10/13; install state machine handles all error cases; library screen functional.

---

## Sprint 13 — Rate limiting + security hardening
**Week 25 | Phase 14**

> 1-week sprint. Rate limiting applied to existing, tested endpoints.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 13.1 | Bucket4j + Redis dependency added to Spring Boot | S | `application.yml` has Redis config; Bucket4j bean starts without error |
| 13.2 | Rate limit filter for `POST /auth/login` — 5/min per IP | M | 6th request from same IP within 1 min → 429 with `X-Rate-Limit-Retry-After-Seconds` header |
| 13.3 | Rate limit filter for `POST /auth/register` — 3/min per IP | S | Same pattern; confirmed independent bucket from login |
| 13.4 | Rate limit for APK upload — 5/hour per userId | S | 6th upload in 1 hour from same userId → 429 |
| 13.5 | Rate limit for download endpoint — 20/min per userId | S | 21st download request in 1 min → 429 |
| 13.6 | Rate limit for search — 60/min per IP | S | 61st search in 1 min from same IP → 429 |
| 13.7 | Rate limit for scanner callback — 10/min per scanner VM IP | S | Excess callbacks → 429 (secondary protection after HMAC) |
| 13.8 | Test two app instances share Redis bucket (multi-instance correctness) | M | Hit main-vm instance A 3 times + instance B 3 times → total 5 allowed, 6th → 429 |

**Sprint 13 definition of done:** All rate limits active; 429 responses include Retry-After header; multi-instance sharing confirmed.

---

## Sprint 14 — Backup, monitoring, and operational hardening
**Week 26 | Phase 15**

> 1-week sprint. Operations, alerts, and the restore drill.

### Tasks

| # | Task | Size | Acceptance criterion |
|---|---|---|---|
| 14.1 | Verify pg_dump cron + GCS retention policy active (daily/weekly/monthly rotation) | S | GCS lifecycle rules visible in console; oldest daily backup older than 7 days is deleted |
| 14.2 | Restore runbook document: step-by-step recovery procedure | M | Document covers: new VM → fetch backup → restore → migrate → verify → switchover |
| 14.3 | Restore drill: spin up fresh VM, restore latest backup, verify row counts | L | Drill completed; row counts match production; service runs against restored DB |
| 14.4 | Cloud Monitoring alert: backup file older than 26 hours | M | Disable cron for 30 min → alert fires |
| 14.5 | Cloud Monitoring alert: 429 error rate spike (>50 in 5 min) | M | Trigger rate limit manually → alert fires within 2 min |
| 14.6 | Cloud Monitoring alert: scanner webhook invalid signature rate > 0 | M | Send fake signature → alert fires |
| 14.7 | Cloud Monitoring alert: VM CPU > 85% sustained 5 min | S | Synthetic load test → alert fires |
| 14.8 | Security audit: confirm no secrets in source code or config files | M | `grep -r "password\|secret\|key" src/` returns only Secret Manager references |

**Sprint 14 definition of done:** Restore drill passed; all four alerts active and tested; no secrets in source code.

---

## Sprint summary

| Sprint | Focus | Weeks | Key output |
|---|---|---|---|
| 1 | Infrastructure | 1–2 | Both VMs live, CI/CD, secrets, backup |
| 2 | Auth | 3–4 | JWT, 3 roles, email verify, token refresh |
| 3 | APK parser | 5 | Parser module with tests |
| 4 | Scanner microservice | 6–7 | HMAC-signed webhook, scan round-trip |
| 5 | File storage + submission | 8–10 | APK upload, ownership binding, scan dispatch |
| 6 | Admin review + tracks | 11–12 | Approve/reject, Alpha/Beta/Prod, rollback |
| 7 | Download + store API | 13–14 | Signed URL download, browse, search |
| 8 | Notifications + ratings | 15–16 | Async email/in-app, verified reviews |
| 9 | Angular: auth + console | 17–18 | Dev console web UI |
| 10 | Angular: store + admin | 19–20 | Public store + admin panel web UI |
| 11 | Flutter: auth + store | 21–22 | Mobile auth, browse, detail |
| 12 | Flutter: download + library | 23–24 | APK install flow, library, real device tests |
| 13 | Rate limiting | 25 | Bucket4j on all endpoints |
| 14 | Backup + monitoring | 26 | Restore drill, alerts, security audit |

---

## Velocity notes

- Sprints 1–8 are pure backend. No frontend work until the API surface is solid.
- Sprint 3 and 13 are 1-week sprints because the scope is tight and self-contained.
- Sprint 5 spans 3 weeks because APK submission integrates storage, parsing, ownership, and scanner dispatch — it is the most connected module in the system.
- Sprint 12 (Flutter install flow) has the highest risk of schedule slip. Book extra time for Android version compatibility testing — Android 8/10/13 all behave differently for `REQUEST_INSTALL_PACKAGES`.

## Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| MobSF static analysis too slow for v1 scan latency | Medium | Return `SCAN_COMPLETE` async; admin sees results when ready, not synchronously |
| GCP credits run out faster than estimated | High | Monitor burn rate monthly; reduce scanner VM to e2-micro if scan volume is low |
| Android install flow fails on specific device OEMs (Samsung, Xiaomi) | High | Test on 3+ real devices in Sprint 12; document known OEM quirks |
| PostgreSQL FTS quality insufficient for search UX | Medium | Meilisearch migration is designed into roadmap; FTS is a drop-in replacement path |
| Resend free tier (3k emails/month) hit by active developer community | Low | Monitor monthly; upgrade Resend plan is cheap; no architecture change needed |