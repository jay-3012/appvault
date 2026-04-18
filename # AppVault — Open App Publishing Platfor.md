# AppVault — Open App Publishing Platform

## What it is

AppVault is a full-stack, self-hostable app marketplace that gives developers a place to publish Android applications and gives users a place to discover and install them — without the gatekeeping, commission cuts, or policy unpredictability of the major app stores.

It has three sides: a developer console where developers submit and manage apps, a user-facing store where people browse and download them, and an admin panel where the platform operator reviews submissions, moderates content, and manages the health of the store.

---

## Problem being solved

Android developers today are entirely dependent on Google Play. That means a 15–30% commission cut, opaque and inconsistent review decisions, no control over distribution, and zero visibility into what actually happens after a user downloads your app. There is no flexible, independent alternative that gives developers genuine control.

AppVault solves this by providing:

- A platform developers own and operate
- A full review workflow with structured, predictable feedback
- Download analytics that belong to the developer
- A user-facing store with search, ratings, and install flow

---

## Platform sides

### Developer console

Everything a developer needs to publish and manage their apps.

| Feature | Description |
|---|---|
| Account registration | Email-verified developer accounts with terms acceptance |
| App submission | APK upload with metadata, screenshots, and content rating |
| APK parsing | Automatic extraction of packageName, versionCode, certHash, permissions, minSdk |
| Version management | Alpha, Beta, Production release tracks with manual promotion |
| Ownership binding | packageName + signing certificate fingerprint locked to first publisher |
| Review tracking | Real-time status: PENDING, APPROVED, REJECTED |
| App listing management | Title, description, icon, screenshots, banner, changelog, privacy policy URL, support email |
| Analytics dashboard | Downloads over time, ratings breakdown, top-performing versions |
| Rejection handling | Structured rejection reasons with notes; edit and resubmit same submission |
| Notifications | Email + in-app alerts for approval, rejection, and review events |

### User store

The public-facing store for discovering and installing apps.

| Feature | Description |
|---|---|
| Browse and discover | Home feed with featured, trending, and new apps |
| Search | Full-text search with filters by category, content rating, and version |
| App detail pages | Description, screenshots, changelog, permissions, ratings |
| Download and install | Signed URL download → Android native installer |
| Ratings and reviews | Star ratings with text reviews; verified-download requirement |
| User library | Download history and version awareness |
| In-app notifications | New version available, update notices |
| Content rating display | EVERYONE / TEEN / MATURE clearly shown per app |

### Admin panel

Platform operator tools for maintaining quality and safety.

| Feature | Description |
|---|---|
| Review queue | Pending submissions with scanner results, metadata, and rating |
| Approve / reject | Structured rejection with required reason category and optional notes |
| Content rating override | Admin can correct or override developer-selected content ratings |
| App takedown | Suspend (temporary) or permanently remove apps with reason |
| Developer account management | View accounts, suspend developers, manage dispute resolution |
| Curated collections | Featured and trending collections edited by admin |
| Platform analytics | Download volume, submission trends, rejection category breakdown |
| Audit logs | Every admin action is logged with timestamp and actor |

---

## App lifecycle

```
Developer submits APK
        ↓
APK parsed (packageName, certHash, versionCode, permissions)
        ↓
Ownership check (packageName + certHash vs existing records)
        ↓
Metadata + content rating collected
        ↓
Async scan dispatched to scanner microservice (HMAC-signed)
        ↓
Risk report returned to main platform
        ↓
Admin review queue (risk report + metadata + rating)
        ↓
    Approve                    Reject (with reason)
        ↓                            ↓
  ALPHA track               Notify developer
        ↓                    Developer edits + resubmits
  Promote: ALPHA → BETA → PRODUCTION
        ↓
  Users can download (via signed URL)
        ↓
  Download event logged (userId, IP, appId, versionId, timestamp)
```

---

## Release track model

| Track | Access | Promotion |
|---|---|---|
| Alpha | Invited tester emails only | Manual by developer, admin approval required |
| Beta | Wider tester list (opt-in) | Manual by developer |
| Production | All users | Manual by developer |

Rules:
- Promotion is sequential only — cannot skip Alpha → Production
- versionCode must always increase; duplicate codes are auto-rejected
- Rollback: developer can mark a previous version as active production
- One active version per track at any time (enforced by DB constraint)

---

## Security controls

| Control | What it prevents |
|---|---|
| Signed URL downloads | Direct GCS URL sharing, download bypass, hotlinking |
| Package name + cert fingerprint binding | App impersonation and malicious repackaging |
| HMAC-SHA256 webhook signing | Fake scanner results being injected into the platform |
| GCP Secret Manager | Secrets in source control or plaintext .env files |
| Bucket4j rate limiting | Brute force, upload spam, download endpoint abuse |
| Content rating enforcement | Platform liability from mislabeled mature content |
| Structured rejection reasons | Inconsistent feedback; developers retrying endlessly without guidance |

---

## Explicitly out of scope (v2)

- In-app purchases and paid apps
- Auto-update SDK
- AAB (Android App Bundle) support
- Crash reporting SDK
- Multi-language / i18n
- Push notifications (using in-app + email only in v1)
- Revenue and commission management

---

## Users of the platform

| User type | What they do |
|---|---|
| Developer | Publishes and manages apps, views analytics, responds to rejections |
| End user | Discovers apps, installs via Android, rates and reviews |
| Platform admin | Reviews submissions, moderates content, manages the store |