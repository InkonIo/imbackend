-- Дни графика/табеля из Таймтрекера: план (start/end), факт (in/out), тип дня.
CREATE TABLE IF NOT EXISTS ext_sheet_day (
  employee_id bigint NOT NULL,
  day date NOT NULL,                -- локальная дата (Asia/Almaty)
  sheet_id bigint,
  type text,                        -- weekend / late / ... (как в Таймтрекере)
  plan_start time, plan_end time,   -- для type='weekend' это заглушка 14:00-15:00, не смена
  fact_in timestamptz, fact_out timestamptz,
  worked_min int NOT NULL DEFAULT 0,
  raw jsonb,
  synced_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (employee_id, day));
CREATE INDEX IF NOT EXISTS ix_ext_sheet_day_day ON ext_sheet_day(day);
