CREATE TABLE telegram_link (
    id        BIGSERIAL PRIMARY KEY,
    user_id   BIGINT      NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    chat_id   BIGINT      NOT NULL UNIQUE,
    username  VARCHAR(64),
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE telegram_link_code (
    code       VARCHAR(32) PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);