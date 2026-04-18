# Roadmap — AppVault

## Philosophy: independent modules first

Rather than building the full system end-to-end and only being able to test it when everything is wired together, each module is built so it can be developed, tested, and validated in isolation. A module is "done" when it has working endpoints, tests, and a clear interface contract — not when it's integrated into the whole.

This approach means:
- You can ship something runnable every 2 weeks
- Bugs are caught in the module they belong to, not during integration
- The most dangerous security controls get built first, before any user-facing features

---

## Module build order rationale

The sequence below is not arbitrary. Each module either unblocks the next one or addresses a risk that would be painful to retrofit later.

| Priority | Module | Why first |
|---|---|---|
| 1 | Infrastructure + secrets | Everything else depends on this |
| 2 | Auth (JWT + roles) | Every API needs auth; build it once cleanly |
| 3 | APK parser | Core data extraction; needed by submission + scanner |
| 4 | Scanner microservice | Isolated; can be built and tested with mock APKs |
| 5 | File storage (GCS) + signed URLs | Blocks both submission and download |
| 6 | App submission API | Core developer workflow |
| 7 | Admin review API | Unblocks the release track |
| 8 | Release tracks | Needed before public download is possible |
| 9 | Download flow | Signed URL + download event logging |
| 10 | User store API | Browse, search, app detail |
| 11 | Notifications | Email + in-app; needed by submission + review |
| 12 | Ratings + reviews | Can be built independently of core flow |
| 13 | Angular web frontend | Built module-by-module, consuming existing APIs |
| 14 | Flutter mobile app | Built last; entire API surface exists by then |
| 15 | Rate limiting | Applied to existing endpoints |
| 16 | Backup + monitoring | Operational hygiene; runs alongside everything |

---

## Phase 0 — Foundation (Week 1–2)

**Goal:** Both VMs provisioned, secrets managed, CI/CD pipeline running, database schema migrated.

Deliverables:
- GCP e2-medium + e2-small VMs provisioned with Docker Compose
- PostgreSQL + Redis running on main-vm
- GCP Secret Manager populated with all runtime secrets
- GitHub Actions pipeline deploys a "hello world" Spring Boot app to main-vm
- GitHub Actions pipeline deploys a "hello world" FastAPI app to scanner-vm
- Initial Flyway/Liquibase DB migrations: users, apps, app_versions, notifications tables
- GCS buckets created: `appvault-files` and `appvault-backups`
- pg_dump cron job running nightly to `appvault-backups`
- Cloudflare pointed at GCS for CDN delivery

Acceptance:
- `GET /health` on both VMs returns 200
- Backup file appears in GCS the morning after setup
- A secret read from Secret Manager is logged (not the value — just that it was retrieved)

---

## Phase 1 — Auth module (Week 3–4)

**Goal:** Complete JWT authentication with three roles. Standalone — no other business logic.

Deliverables:
- `POST /auth/register` — creates user, sends verification email via Resend
- `POST /auth/verify-email` — confirms account
- `POST /auth/login` — returns access token (15 min) + refresh token (7 days)
- `POST /auth/refresh` — rotates refresh token
- `POST /auth/logout` — blacklists token in Redis
- Spring Security config: USER / DEVELOPER / ADMIN role guards
- JWT filter on all protected routes
- Resend email integration (async `@Async` method)

Acceptance:
- Cannot call protected routes without a valid token → 401
- Expired token → 401; refresh works → new token issued
- Logout invalidates token immediately (Redis blacklist check)
- Role guard: ADMIN endpoint returns 403 for USER role token
- Email arrives for registration verification

---

## Phase 2 — APK parser module (Week 5)

**Goal:** Given an APK file path, extract all metadata reliably. Standalone Python module.

Deliverables:
- Python module `apk_parser.py` using AndroGuard + APKTool
- Extracts: `packageName`, `versionCode`, `versionName`, `minSdk`, `targetSdk`, `certFingerprint`, `permissions[]`, `iconBytes`
- Unit tests with real APK fixtures (min 5 different APKs)
- FastAPI endpoint `POST /parse` accepts APK URL, returns parsed metadata JSON
- Version code validation: rejects if not higher than latest existing version

Acceptance:
- Parser correctly extracts all fields from 5 test APKs
- Certificate fingerprint is stable across re-runs of same APK
- Duplicate versionCode on same packageName returns validation error

---

## Phase 3 — Scanner microservice (Week 6–7)

**Goal:** Isolated scanner VM receives APK URL, analyzes it, returns signed risk report. No integration with main platform yet — tested via direct HTTP calls.

Deliverables:
- FastAPI `POST /scan` endpoint on scanner-vm
  - Validates HMAC signature on incoming request
  - Downloads APK from GCS URL
  - Runs APK parser (Phase 2)
  - Runs MobSF static analysis
  - Assembles risk report JSON
  - Signs outgoing webhook with HMAC
  - POSTs result to main platform callback URL
- Redis Celery queue for concurrent scan jobs
- HMAC signing/verification with shared secret from Secret Manager
- `callbackId` included for replay protection

Acceptance:
- Fake HMAC on inbound request → 401
- Scan of a known-clean APK returns `scanStatus: PASSED`
- Scan of a known-suspicious APK (excessive permissions, dynamic code loading) returns `scanStatus: FLAGGED`
- Duplicate `callbackId` in Redis → 200 returned, no second processing
- Main platform endpoint (mocked) receives signed webhook with correct HMAC

---

## Phase 4 — File storage module (Week 8)

**Goal:** GCS integration with signed URL generation. Tested in isolation before being used by submission or download.

Deliverables:
- Spring Boot `StorageService` with methods:
  - `uploadApk(appId, versionId, inputStream)` → GCS path
  - `uploadScreenshot(appId, filename, inputStream)` → GCS path
  - `generateSignedDownloadUrl(gcsPath, ttlMinutes)` → signed URL
  - `deleteObject(gcsPath)`
- GCS bucket IAM: no public access; service account write-only
- Signed URL TTL: 8 minutes (configurable)
- Integration test: upload file → generate signed URL → download succeeds → URL expires → download returns 403

Acceptance:
- Direct GCS URL for uploaded object returns 403 (bucket not public)
- Signed URL works during TTL window, returns 403 after expiry
- Upload of >100MB file completes without timeout

---

## Phase 5 — App submission API (Week 9–10)

**Goal:** Developer can submit an app end-to-end. APK uploaded, parsed, ownership checked, scanner job dispatched, status tracked.

Deliverables:
- `POST /developer/apps` — create app listing shell (title, description, category, content rating)
- `POST /developer/apps/{appId}/versions` — upload APK + screenshots + metadata
  - Validates JWT (DEVELOPER role)
  - Stores APK via StorageService
  - Calls APK parser
  - Runs ownership check (packageName + certHash vs DB)
  - Dispatches async scan job to scanner-vm (HMAC-signed POST)
  - Sets version status to `PENDING_SCAN`
- `GET /developer/apps` — list developer's apps with status
- `GET /developer/apps/{appId}/versions` — version history with track and status
- `PATCH /developer/apps/{appId}` — update listing metadata
- Ownership table: `app_ownership(packageName, certFingerprint, developerAccountId)`

Acceptance:
- Submitting APK with same packageName from different developer account → 409 Conflict
- Submitting APK with lower versionCode than existing → 422
- After submission, version status is `PENDING_SCAN`
- Scanner-vm receives signed webhook (verified by checking HMAC)
- After scanner callback, version status updates to `SCAN_COMPLETE`

---

## Phase 6 — Admin review API (Week 11)

**Goal:** Admin can review pending submissions, approve, reject with structured reasons, and manage the queue.

Deliverables:
- `GET /admin/review-queue` — paginated list of apps pending review, with scan results
- `POST /admin/apps/{appId}/versions/{versionId}/approve` — moves to ALPHA track
- `POST /admin/apps/{appId}/versions/{versionId}/reject` — requires `rejectionReason` enum + optional `notes`
- Rejection reason enum: `MALWARE_DETECTED | POLICY_VIOLATION | CONTENT_MISMATCH | INCOMPLETE_METADATA | COPYRIGHT_ISSUE | INVALID_APK`
- `PATCH /admin/apps/{appId}/content-rating` — admin override for content rating
- `POST /admin/apps/{appId}/suspend` — suspend app (hidden from store)
- `POST /admin/apps/{appId}/remove` — permanent removal; packageName blocked from re-submission without admin unlock
- Audit log table: every admin action logged with `adminId`, `action`, `targetId`, `timestamp`, `notes`

Acceptance:
- Reject without providing reason enum → 400 Bad Request
- Approved app version appears in ALPHA track
- Rejected app cannot be promoted; developer must resubmit
- Audit log entry exists for every approve/reject/suspend/remove action

---

## Phase 7 — Release tracks (Week 12)

**Goal:** Alpha, Beta, Production tracks with promotion, rollback, and tester access control.

Deliverables:
- `POST /developer/apps/{appId}/versions/{versionId}/promote` — Alpha → Beta → Production
- `POST /developer/apps/{appId}/versions/{versionId}/rollback` — mark older version as active Production
- `GET /apps/{appId}` — returns active Production version only (public endpoint)
- Tester management:
  - `POST /developer/apps/{appId}/testers` — add tester email for Alpha/Beta
  - `GET /developer/apps/{appId}/testers` — list testers
- DB constraint: only one `isActive = true` version per track per app (enforced at DB level)
- Version state machine: `PENDING_SCAN → SCAN_COMPLETE → APPROVED (ALPHA) → BETA → PRODUCTION`

Acceptance:
- Cannot promote Alpha → Production directly (skipping Beta) → 422
- Rollback: older version becomes active; newer version `isActive = false`
- DB uniqueness constraint prevents two active versions on same track
- Non-tester account cannot download an Alpha build → 403

---

## Phase 8 — Download flow (Week 13)

**Goal:** Every APK download is tracked and served via short-lived signed URL. No direct GCS access.

Deliverables:
- `GET /apps/{appId}/download` — the only download endpoint
  - Validates JWT
  - Confirms app version is `PRODUCTION` and `isActive = true`
  - Writes download event: `userId`, `appId`, `versionId`, `IP`, `timestamp`
  - Generates 8-minute signed GCS URL
  - Returns HTTP 302 redirect
- Download event table: `download_events(id, userId, appId, versionId, ipAddress, createdAt)`
- GCS bucket policy: no public object access (all access via signed URLs only)

Acceptance:
- Direct GCS object URL → 403 (no public access)
- Download endpoint without valid JWT → 401
- Download endpoint with valid JWT → 302 to signed URL → APK downloads successfully
- Second request with same signed URL after TTL → 403
- Download event row exists in DB after successful download

---

## Phase 9 — User store API (Week 14)

**Goal:** Public APIs for browsing, searching, and viewing apps. Powers both Angular store and Flutter app.

Deliverables:
- `GET /apps` — browse with filters: `category`, `contentRating`, `sort` (newest, mostDownloaded, rating)
- `GET /apps/featured` — admin-curated featured collection
- `GET /apps/search?q=` — full-text search (PostgreSQL FTS on title + description)
- `GET /apps/{appId}` — full app detail: description, screenshots, permissions, changelog, ratings summary
- `GET /apps/{appId}/versions/{versionId}/changelog` — version-specific changelog
- `GET /categories` — list all categories
- Caching: Redis cache on `/apps/featured` and category listing (5-minute TTL)

Acceptance:
- Search for exact app title returns that app as first result
- Filter by `contentRating=EVERYONE` returns no MATURE apps
- App detail includes permissions list extracted by parser
- Featured endpoint returns cached result on second call (verify via response time)

---

## Phase 10 — Notifications (Week 15)

**Goal:** Event-driven email and in-app notifications for all key lifecycle events.

Deliverables:
- Notification event enum: `APP_APPROVED | APP_REJECTED | NEW_VERSION_AVAILABLE | PASSWORD_RESET | ACCOUNT_VERIFIED`
- In-app notification table: `notifications(id, userId, type, title, message, isRead, createdAt)`
- `GET /notifications` — list unread notifications for authenticated user
- `PATCH /notifications/{id}/read` — mark as read
- `PATCH /notifications/read-all` — mark all as read
- Resend email templates for each event type
- All email sends are `@Async` — never block the request thread
- Retry logic: 3 attempts with exponential backoff on Resend API failure
- Events fire for: submission received, approved, rejected, version promoted to production

Acceptance:
- App approval → developer receives email within 30 seconds and in-app notification appears
- Email send failure → retried 3 times; failure logged; does not crash the request
- `GET /notifications` returns only current user's notifications (no cross-user leakage)

---

## Phase 11 — Ratings and reviews (Week 16)

**Goal:** Users who have downloaded an app can rate and review it. Reviews are moderated.

Deliverables:
- `POST /apps/{appId}/reviews` — submit review (requires download event in DB for this user + app)
- `GET /apps/{appId}/reviews` — paginated reviews with rating breakdown
- `PATCH /admin/reviews/{reviewId}/moderate` — admin hide/show review
- Rating aggregate: stored as `averageRating` and `ratingCount` on app record (updated on each new review)
- Duplicate review prevention: one review per user per app

Acceptance:
- User who has not downloaded the app cannot submit review → 403
- Second review from same user on same app → 409
- Admin hides review → it no longer appears in public listing
- Rating aggregate updates correctly after new review

---

## Phase 12 — Angular frontend (Week 17–20)

**Goal:** Web frontend for all three surfaces, consuming the API surface built in Phases 1–11.

Build order within this phase:

**Week 17:** Auth + developer console skeleton
- Login, register, email verification
- Developer console layout: sidebar, routing
- App listing management page

**Week 18:** Developer console features
- APK submission form with drag-and-drop upload
- Version management and release track promotion UI
- Analytics dashboard (download chart, rating breakdown)

**Week 19:** User store
- Home feed with featured + categories
- Search page with filters
- App detail page with screenshots, permissions, reviews

**Week 20:** Admin panel
- Review queue with approve/reject actions
- App management (suspend, remove, rating override)
- Platform analytics

---

## Phase 13 — Flutter mobile app (Week 21–24)

**Goal:** Android app for the user store. All APIs exist; this phase is purely mobile implementation.

**Week 21:** Auth + navigation
- Login, register, email verification
- Bottom nav: Store, Search, Library, Profile

**Week 22:** Browse + search
- Home feed
- Search with filters
- App detail page

**Week 23:** Download + install flow
- Permission check for `REQUEST_INSTALL_PACKAGES`
- Download progress indicator
- Android package installer trigger via `open_file`
- Full state machine: IDLE → DOWNLOADING → READY → INSTALLING → DONE

**Week 24:** Library + notifications
- Download history
- Version awareness (new version available badge)
- In-app notification feed

---

## Phase 14 — Rate limiting + hardening (Week 25)

**Goal:** Apply Bucket4j rate limiting to all endpoints, tighten security surface.

Deliverables:
- Rate limit config per endpoint:

| Endpoint | Key | Limit |
|---|---|---|
| `POST /auth/login` | IP | 5/min |
| `POST /auth/register` | IP | 3/min |
| `POST /developer/apps/*/versions` | userId | 5 uploads/hour |
| `GET /apps/*/download` | userId | 20/min |
| `GET /apps/search` | IP | 60/min |
| `POST /scanner/callback` | scanner VM IP | 10/min |

- HTTP 429 with `X-Rate-Limit-Retry-After-Seconds` header on all limit exceeded responses
- Redis-backed distributed limits (works across multiple app instances)

Acceptance:
- Login endpoint: 6th request within 1 minute from same IP → 429 with Retry-After header
- Authenticated download: 21st request within 1 minute → 429
- Two app instances share the same limit counter (Redis key is the same)

---

## Phase 15 — Backup + operational hardening (Week 26)

**Goal:** Verified backup and restore, monitoring alerts, restore drill documented.

Deliverables:
- Nightly `pg_dump` cron to GCS with daily/weekly/monthly retention
- GCS lifecycle policy: delete backups older than 30 days
- Restore runbook document (step-by-step, estimated 4-hour RTO)
- Restore drill: spin up fresh VM, restore latest backup, verify row counts
- Cloud Monitoring alerts:
  - Backup file older than 26 hours → alert
  - 429 error rate spike → alert
  - Scanner webhook invalid signature rate > 0 → alert
  - VM CPU > 85% sustained 5 minutes → alert

Acceptance:
- Full restore drill completed: fresh PostgreSQL instance + backup restore + data verification
- Backup alert fires when cron job manually disabled for 30 minutes
- Monitoring dashboard shows all four alert conditions

---

## Summary timeline

| Phase | Module | Weeks | Output |
|---|---|---|---|
| 0 | Infrastructure | 1–2 | Both VMs live, CI/CD, secrets, DB schema |
| 1 | Auth | 3–4 | JWT auth, 3 roles, email verify |
| 2 | APK parser | 5 | Parsing module with tests |
| 3 | Scanner | 6–7 | Isolated scanner with HMAC webhook |
| 4 | File storage | 8 | GCS service + signed URLs |
| 5 | Submission API | 9–10 | Developer can submit apps |
| 6 | Admin review | 11 | Admin can approve/reject |
| 7 | Release tracks | 12 | Alpha/Beta/Prod with rollback |
| 8 | Download flow | 13 | Tracked, signed URL downloads |
| 9 | Store API | 14 | Browse, search, app detail |
| 10 | Notifications | 15 | Email + in-app events |
| 11 | Ratings | 16 | Reviews with download verification |
| 12 | Angular web | 17–20 | All three web surfaces |
| 13 | Flutter app | 21–24 | Android mobile store |
| 14 | Rate limiting | 25 | Bucket4j on all endpoints |
| 15 | Backup + ops | 26 | Verified backup, monitoring alerts |

Total: **26 weeks** from infrastructure to production-hardened platform.