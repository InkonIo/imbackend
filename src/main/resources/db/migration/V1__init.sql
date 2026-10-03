CREATE TABLE city (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE outlet (
    id      BIGSERIAL PRIMARY KEY,
    city_id BIGINT       NOT NULL REFERENCES city (id),
    name    VARCHAR(150) NOT NULL,
    address VARCHAR(255),
    active  BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (city_id, name)
);

CREATE TABLE app_user (
    id                   BIGSERIAL PRIMARY KEY,
    login                VARCHAR(64)  NOT NULL UNIQUE,
    password_hash        VARCHAR(100),
    full_name            VARCHAR(150) NOT NULL,
    account_role         VARCHAR(20)  NOT NULL,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_role CHECK (account_role IN ('SUPER_ADMIN', 'DIRECTOR', 'MANAGER'))
);

CREATE TABLE user_outlet (
    user_id   BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    outlet_id BIGINT NOT NULL REFERENCES outlet (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, outlet_id)
);

CREATE TABLE shift_session (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id),
    outlet_id   BIGINT      NOT NULL REFERENCES outlet (id),
    shift_role  VARCHAR(30) NOT NULL,
    day_part    VARCHAR(10) NOT NULL,
    shift_date  DATE        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT chk_shift_role CHECK (shift_role IN ('INSIDE', 'PRODUCTION_MANAGER', 'SERVICE_MANAGER')),
    CONSTRAINT chk_day_part CHECK (day_part IN ('MORNING', 'EVENING'))
);

CREATE INDEX idx_shift_user_date ON shift_session (user_id, shift_date);
CREATE INDEX idx_shift_outlet_date ON shift_session (outlet_id, shift_date);