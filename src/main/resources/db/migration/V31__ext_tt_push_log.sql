-- Журнал отправок в Таймтрекер: что было в дне до и что отправили после. По нему можно откатить день.
CREATE TABLE IF NOT EXISTS ext_tt_push_log (
  id bigserial PRIMARY KEY,
  employee_id bigint NOT NULL,
  day date NOT NULL,
  before_day jsonb,
  after_day jsonb,
  status text NOT NULL,          -- OK / MISMATCH / ERROR
  http_status int,
  message text,
  pushed_by bigint,
  pushed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_ext_tt_push_log_emp ON ext_tt_push_log(employee_id, day);