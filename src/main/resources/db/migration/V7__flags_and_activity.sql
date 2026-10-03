ALTER TABLE checklist_photo ADD COLUMN sha256 VARCHAR(64);
ALTER TABLE checklist_photo ADD COLUMN taken_at TIMESTAMPTZ;
CREATE INDEX ix_photo_sha256 ON checklist_photo (sha256);

CREATE TABLE audit_flag (
    id             BIGSERIAL PRIMARY KEY,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    shift_id       BIGINT       NOT NULL REFERENCES shift_session (id) ON DELETE CASCADE,
    run_item_id    BIGINT       REFERENCES checklist_run_item (id) ON DELETE CASCADE,
    photo_id       BIGINT       REFERENCES checklist_photo (id) ON DELETE SET NULL,
    user_id        BIGINT       NOT NULL,
    outlet_id      BIGINT       NOT NULL,
    type           VARCHAR(30)  NOT NULL,
    severity       VARCHAR(10)  NOT NULL,
    details        VARCHAR(500),
    review_status  VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    reviewed_by    BIGINT,
    reviewed_at    TIMESTAMPTZ,
    review_comment VARCHAR(500)
);
CREATE INDEX ix_flag_shift       ON audit_flag (shift_id);
CREATE INDEX ix_flag_outlet_time ON audit_flag (outlet_id, created_at DESC);
CREATE INDEX ix_flag_item_type   ON audit_flag (run_item_id, type);

-- одна строка = одна минута, когда сайт был открыт
CREATE TABLE activity_minute (
    user_id   BIGINT      NOT NULL,
    minute    TIMESTAMPTZ NOT NULL,
    shift_id  BIGINT      NOT NULL REFERENCES shift_session (id) ON DELETE CASCADE,
    device_id VARCHAR(64),
    PRIMARY KEY (user_id, minute)
);
CREATE INDEX ix_activity_shift ON activity_minute (shift_id, minute);