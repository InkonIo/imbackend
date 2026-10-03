CREATE TABLE item_review (
    id             BIGSERIAL PRIMARY KEY,
    run_item_id    BIGINT       NOT NULL UNIQUE REFERENCES checklist_run_item (id) ON DELETE CASCADE,
    shift_id       BIGINT       NOT NULL REFERENCES shift_session (id) ON DELETE CASCADE,
    outlet_id      BIGINT       NOT NULL,
    decision       VARCHAR(20)  NOT NULL,
    comment        VARCHAR(500),
    reviewer_id    BIGINT       NOT NULL,
    reviewer_login VARCHAR(64),
    reviewed_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_item_review_shift ON item_review (shift_id);

-- быстрый поиск непроверенных флагов
CREATE INDEX ix_flag_open_outlet ON audit_flag (outlet_id, created_at DESC) WHERE review_status = 'OPEN';