CREATE TABLE hard_deletion_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    reference VARCHAR(100),
    reason VARCHAR(500) NOT NULL,
    deleted_by UUID NOT NULL REFERENCES users(id),
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX hard_deletion_audit_entity ON hard_deletion_audit(entity_type, entity_id);
