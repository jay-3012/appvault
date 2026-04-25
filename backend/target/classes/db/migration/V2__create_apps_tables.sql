CREATE TYPE app_status AS ENUM ('DRAFT', 'ACTIVE', 'SUSPENDED', 'REMOVED');
CREATE TYPE version_status AS ENUM (
    'PENDING_SCAN',
    'SCAN_COMPLETE',
    'APPROVED',
    'REJECTED',
    'SUPERSEDED'
);
CREATE TYPE release_track AS ENUM ('ALPHA', 'BETA', 'PRODUCTION');
CREATE TYPE content_rating AS ENUM ('EVERYONE', 'TEEN', 'MATURE');

CREATE TABLE apps (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    developer_id     UUID NOT NULL REFERENCES users(id),
    title            VARCHAR(255) NOT NULL,
    short_description VARCHAR(500),
    full_description TEXT,
    category         VARCHAR(100),
    content_rating   content_rating NOT NULL DEFAULT 'EVERYONE',
    status           app_status NOT NULL DEFAULT 'DRAFT',
    icon_gcs_path    VARCHAR(500),
    banner_gcs_path  VARCHAR(500),
    package_name     VARCHAR(255),
    average_rating   DECIMAL(3,2) DEFAULT 0.00,
    rating_count     INTEGER DEFAULT 0,
    total_downloads  BIGINT DEFAULT 0,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_apps_developer_id ON apps(developer_id);
CREATE INDEX idx_apps_status ON apps(status);
CREATE INDEX idx_apps_category ON apps(category);

CREATE TABLE app_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id            UUID NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
    version_code      INTEGER NOT NULL,
    version_name      VARCHAR(100) NOT NULL,
    changelog         TEXT,
    apk_gcs_path      VARCHAR(500),
    apk_size_bytes    BIGINT,
    min_sdk           INTEGER,
    target_sdk        INTEGER,
    cert_fingerprint  VARCHAR(500),
    permissions       TEXT[],
    status            version_status NOT NULL DEFAULT 'PENDING_SCAN',
    track             release_track,
    is_active         BOOLEAN DEFAULT FALSE,
    risk_score        INTEGER,
    scan_flags        TEXT[],
    rejection_reason  VARCHAR(100),
    rejection_notes   TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_versions_app_id ON app_versions(app_id);
CREATE INDEX idx_app_versions_status ON app_versions(status);

-- Ensure only one active version per track per app
CREATE UNIQUE INDEX idx_one_active_per_track
    ON app_versions(app_id, track)
    WHERE is_active = TRUE AND track IS NOT NULL;

CREATE TABLE app_ownership (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    package_name      VARCHAR(255) NOT NULL UNIQUE,
    cert_fingerprint  VARCHAR(500) NOT NULL,
    developer_id      UUID NOT NULL REFERENCES users(id),
    registered_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_ownership_package ON app_ownership(package_name);
CREATE INDEX idx_app_ownership_developer ON app_ownership(developer_id);

CREATE TABLE scan_jobs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id    UUID NOT NULL REFERENCES app_versions(id),
    callback_id   VARCHAR(255) NOT NULL UNIQUE,
    status        VARCHAR(50) NOT NULL DEFAULT 'DISPATCHED',
    dispatched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_scan_jobs_callback_id ON scan_jobs(callback_id);
CREATE INDEX idx_scan_jobs_version_id ON scan_jobs(version_id);
