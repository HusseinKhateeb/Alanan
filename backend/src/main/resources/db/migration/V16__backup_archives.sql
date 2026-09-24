CREATE TABLE backup_archives (
    id UUID PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    source VARCHAR(12) NOT NULL CHECK (source IN ('EXPORT','UPLOAD')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX backup_archives_created_at ON backup_archives(created_at DESC, id);

CREATE TABLE backup_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    archive_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    details JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
