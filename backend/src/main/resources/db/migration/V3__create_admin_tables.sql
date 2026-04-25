CREATE TYPE rejection_reason AS ENUM (
    'MALWARE_DETECTED',
    'POLICY_VIOLATION',
    'CONTENT_MISMATCH',
    'INCOMPLETE_METADATA',
    'COPYRIGHT_ISSUE',
    'INVALID_APK'
);

CREATE TABLE audit_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID NOT NULL REFERENCES users(id),
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id   VARCHAR(255) NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_admin_id ON audit_logs(admin_id);
CREATE INDEX idx_audit_logs_target_id ON audit_logs(target_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);

CREATE TABLE app_testers (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id     UUID NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
    email      VARCHAR(255) NOT NULL,
    track      release_track NOT NULL DEFAULT 'ALPHA',
    added_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(app_id, email, track)
);

CREATE INDEX idx_app_testers_app_id ON app_testers(app_id);
CREATE INDEX idx_app_testers_email ON app_testers(email);
