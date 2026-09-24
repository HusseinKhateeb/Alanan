CREATE TABLE user_permission_overrides (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 permission_id BIGINT NOT NULL REFERENCES permissions(id),
 granted BOOLEAN NOT NULL,
 PRIMARY KEY (user_id, permission_id)
);

CREATE TABLE user_audit (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 target_user_id UUID NOT NULL REFERENCES users(id),
 user_id UUID NOT NULL REFERENCES users(id),
 action VARCHAR(30) NOT NULL,
 new_values JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX user_audit_created_at ON user_audit(created_at DESC, id DESC);
