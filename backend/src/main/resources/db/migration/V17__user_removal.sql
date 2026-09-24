ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX users_visible_username_idx ON users (username) WHERE deleted_at IS NULL;
