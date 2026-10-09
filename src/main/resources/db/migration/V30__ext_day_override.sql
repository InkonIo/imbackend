-- Правки дней графика, сделанные на сайте. Таймтрекер не трогаем: синхронизация перезаписывает
-- ext_sheet_day, а правки лежат отдельно и накладываются поверх через представление ext_sheet_day_eff.
CREATE TABLE IF NOT EXISTS ext_day_override (
  employee_id bigint NOT NULL,
  day date NOT NULL,
  kind text NOT NULL CHECK (kind IN ('SHIFT', 'OFF')),
  start_time time,
  end_time time,
  note text,
  edited_by bigint,
  edited_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (employee_id, day),
  CHECK (kind = 'OFF' OR (start_time IS NOT NULL AND end_time IS NOT NULL))
);

-- Журнал: кто, когда и что было до / стало после.
CREATE TABLE IF NOT EXISTS ext_day_override_log (
  id bigserial PRIMARY KEY,
  employee_id bigint NOT NULL,
  day date NOT NULL,
  before_text text,
  after_text text,
  edited_by bigint,
  edited_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_ext_day_override_log_emp ON ext_day_override_log(employee_id, day);

-- То же, что ext_sheet_day, но с наложенными правками. Колонки совпадают, поэтому в запросах достаточно сменить имя таблицы.
CREATE OR REPLACE VIEW ext_sheet_day_eff AS
SELECT coalesce(d.employee_id, o.employee_id) AS employee_id,
       coalesce(d.day, o.day) AS day,
       d.sheet_id,
       CASE WHEN o.kind = 'OFF' THEN 'weekend'
            WHEN o.kind = 'SHIFT' THEN CASE WHEN d.type IN ('was', 'late', 'wasnt', 'wasInWeekend') THEN d.type ELSE 'work' END
            ELSE d.type END AS type,
       CASE WHEN o.kind = 'OFF' THEN NULL WHEN o.kind = 'SHIFT' THEN o.start_time ELSE d.plan_start END AS plan_start,
       CASE WHEN o.kind = 'OFF' THEN NULL WHEN o.kind = 'SHIFT' THEN o.end_time ELSE d.plan_end END AS plan_end,
       d.fact_in, d.fact_out,
       coalesce(d.worked_min, 0) AS worked_min,
       d.raw, d.synced_at,
       (o.employee_id IS NOT NULL) AS edited,
       d.type AS tt_type, d.plan_start AS tt_start, d.plan_end AS tt_end
FROM ext_sheet_day d
FULL JOIN ext_day_override o ON o.employee_id = d.employee_id AND o.day = d.day;