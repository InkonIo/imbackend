-- одна открытая смена на пользователя
CREATE UNIQUE INDEX ux_shift_open_per_user
    ON shift_session (user_id) WHERE finished_at IS NULL;

CREATE TABLE checklist_template (
    id         BIGSERIAL PRIMARY KEY,
    shift_role VARCHAR(30)  NOT NULL,
    day_part   VARCHAR(10)  NOT NULL,
    title      VARCHAR(150) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (shift_role, day_part)
);

CREATE TABLE checklist_section (
    id          BIGSERIAL PRIMARY KEY,
    template_id BIGINT       NOT NULL REFERENCES checklist_template (id) ON DELETE CASCADE,
    title       VARCHAR(150) NOT NULL,
    sort_order  INTEGER      NOT NULL,
    UNIQUE (template_id, sort_order)
);

CREATE TABLE checklist_item (
    id              BIGSERIAL PRIMARY KEY,
    section_id      BIGINT       NOT NULL REFERENCES checklist_section (id) ON DELETE CASCADE,
    title           VARCHAR(500) NOT NULL,
    sort_order      INTEGER      NOT NULL,
    duration_min    INTEGER,
    due_from        TIME,
    due_to          TIME,
    photo_mode      VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    weekday         INTEGER CHECK (weekday BETWEEN 1 AND 7),
    director_review BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE checklist_run (
    id          BIGSERIAL PRIMARY KEY,
    shift_id    BIGINT      NOT NULL UNIQUE REFERENCES shift_session (id) ON DELETE CASCADE,
    template_id BIGINT      NOT NULL REFERENCES checklist_template (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE checklist_run_item (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES checklist_run (id) ON DELETE CASCADE,
    item_id         BIGINT       REFERENCES checklist_item (id) ON DELETE SET NULL,
    section_order   INTEGER      NOT NULL,
    section_title   VARCHAR(150) NOT NULL,
    sort_order      INTEGER      NOT NULL,
    title           VARCHAR(500) NOT NULL,
    duration_min    INTEGER,
    due_from        TIME,
    due_to          TIME,
    photo_mode      VARCHAR(20)  NOT NULL,
    director_review BOOLEAN      NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    comment         VARCHAR(1000),
    done_at         TIMESTAMPTZ
);
CREATE INDEX ix_run_item_run ON checklist_run_item (run_id);

CREATE TABLE checklist_photo (
    id          BIGSERIAL PRIMARY KEY,
    run_item_id BIGINT       NOT NULL REFERENCES checklist_run_item (id) ON DELETE CASCADE,
    file_path   VARCHAR(500) NOT NULL,
    uploaded_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);