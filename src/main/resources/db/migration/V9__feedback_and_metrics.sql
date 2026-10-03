-- обратная связь сотрудникам
CREATE TABLE user_notification (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type        VARCHAR(30)   NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(1000),
    shift_id    BIGINT        REFERENCES shift_session (id) ON DELETE CASCADE,
    run_item_id BIGINT        REFERENCES checklist_run_item (id) ON DELETE SET NULL,
    flag_id     BIGINT        REFERENCES audit_flag (id) ON DELETE SET NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ
);
CREATE INDEX ix_notification_user   ON user_notification (user_id, created_at DESC);
CREATE INDEX ix_notification_unread ON user_notification (user_id) WHERE read_at IS NULL;

-- одна строка = итоги одной смены, готовые для дашборда
CREATE TABLE shift_metrics (
    shift_id        BIGINT      PRIMARY KEY REFERENCES shift_session (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL,
    outlet_id       BIGINT      NOT NULL,
    shift_date      DATE        NOT NULL,
    day_part        VARCHAR(10) NOT NULL,
    shift_role      VARCHAR(30) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    duration_min    INTEGER,
    active_min      INTEGER     NOT NULL DEFAULT 0,
    items_total     INTEGER     NOT NULL DEFAULT 0,
    items_done      INTEGER     NOT NULL DEFAULT 0,
    items_problem   INTEGER     NOT NULL DEFAULT 0,
    items_skipped   INTEGER     NOT NULL DEFAULT 0,
    items_not_done  INTEGER     NOT NULL DEFAULT 0,
    completion_pct  INTEGER     NOT NULL DEFAULT 0,
    due_total       INTEGER     NOT NULL DEFAULT 0,
    due_on_time     INTEGER     NOT NULL DEFAULT 0,
    on_time_pct     INTEGER,
    photos_total    INTEGER     NOT NULL DEFAULT 0,
    comments_total  INTEGER     NOT NULL DEFAULT 0,
    flags_total     INTEGER     NOT NULL DEFAULT 0,
    flags_high      INTEGER     NOT NULL DEFAULT 0,
    flags_open      INTEGER     NOT NULL DEFAULT 0,
    flags_confirmed INTEGER     NOT NULL DEFAULT 0,
    flags_dismissed INTEGER     NOT NULL DEFAULT 0,
    too_fast        INTEGER     NOT NULL DEFAULT 0,
    late            INTEGER     NOT NULL DEFAULT 0,
    items_approved  INTEGER     NOT NULL DEFAULT 0,
    items_rejected  INTEGER     NOT NULL DEFAULT 0,
    score           INTEGER,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_metrics_user_date   ON shift_metrics (user_id, shift_date);
CREATE INDEX ix_metrics_outlet_date ON shift_metrics (outlet_id, shift_date);