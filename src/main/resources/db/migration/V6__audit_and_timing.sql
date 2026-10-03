ALTER TABLE checklist_run_item ADD COLUMN started_at TIMESTAMPTZ;

-- журнал: без внешних ключей специально, записи должны пережить удаление пользователя или точки
CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    type        VARCHAR(40)   NOT NULL,
    user_id     BIGINT,
    user_login  VARCHAR(64),
    shift_id    BIGINT,
    outlet_id   BIGINT,
    entity_type VARCHAR(40),
    entity_id   BIGINT,
    details     VARCHAR(1000),
    ip          VARCHAR(64),
    user_agent  VARCHAR(300),
    device_id   VARCHAR(64)
);

CREATE INDEX ix_audit_user_time   ON audit_event (user_id, created_at DESC);
CREATE INDEX ix_audit_shift       ON audit_event (shift_id);
CREATE INDEX ix_audit_outlet_time ON audit_event (outlet_id, created_at DESC);
CREATE INDEX ix_audit_type_time   ON audit_event (type, created_at DESC);

-- запрет на изменение и удаление записей журнала
CREATE FUNCTION audit_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();