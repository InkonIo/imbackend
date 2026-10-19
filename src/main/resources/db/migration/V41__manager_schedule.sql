-- Лист (неделя) расписания менеджеров
CREATE TABLE manager_week (
    id         BIGSERIAL PRIMARY KEY,
    outlet_id  BIGINT       NOT NULL,
    week_start DATE         NOT NULL,  -- всегда понедельник
    title      VARCHAR(100),           -- необязательный заголовок
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (outlet_id, week_start)
);

-- Ячейка расписания: один менеджер, один день
CREATE TABLE manager_schedule (
    id           BIGSERIAL PRIMARY KEY,
    week_id      BIGINT       NOT NULL REFERENCES manager_week(id) ON DELETE CASCADE,
    outlet_id    BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    sched_date   DATE         NOT NULL,
    shift_start  TIME,                  -- начало смены, напр. 08:00
    shift_end    TIME,                  -- конец смены,  напр. 16:30
    day_off      BOOLEAN      NOT NULL DEFAULT false,
    comment      TEXT,                  -- задачи на день
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (week_id, user_id, sched_date)
);

CREATE INDEX ON manager_week (outlet_id, week_start);
CREATE INDEX ON manager_schedule (week_id);
CREATE INDEX ON manager_schedule (outlet_id, sched_date);