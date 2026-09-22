ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX users_username_case_insensitive ON users (lower(username));

CREATE TABLE auth_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    action VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX auth_events_created_at ON auth_events (created_at);
