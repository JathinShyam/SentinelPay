-- Customer identities for session-based authentication.
-- Accounts remain owned by a stable identifier: the UUID rendered as text.
CREATE TABLE app_users (
    user_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64) NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20) NOT NULL DEFAULT 'USER'
                  CHECK (role IN ('USER', 'ADMIN')),
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_username_length CHECK (char_length(username) BETWEEN 3 AND 64),
    CONSTRAINT chk_display_name_length CHECK (char_length(display_name) BETWEEN 1 AND 128)
);

-- Usernames are case-insensitive. The application normalises to lower case,
-- and the expression index is the durable race-proof backstop.
CREATE UNIQUE INDEX ux_app_users_username_lower
ON app_users (LOWER(username));

CREATE INDEX idx_app_users_role_status ON app_users (role, status);
