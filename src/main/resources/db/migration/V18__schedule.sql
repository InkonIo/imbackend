-- настройки сотрудника для графика
CREATE TABLE staff_profile (
    user_id         BIGINT      PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    job_title       VARCHAR(20) NOT NULL DEFAULT 'MANAGER',
    can_inside      BOOLEAN     NOT NULL DEFAULT TRUE,
    schedulable     BOOLEAN     NOT NULL DEFAULT TRUE,
    max_shifts_week INTEGER     NOT NULL DEFAULT 5
);

-- отпуск / больничный / другое
CREATE TABLE staff_absence (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind       VARCHAR(20)  NOT NULL,
    date_from  DATE         NOT NULL,
    date_to    DATE         NOT NULL,
    note       VARCHAR(300),
    created_by BIGINT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (date_to >= date_from)
);
CREATE INDEX ix_absence_user_dates ON staff_absence (user_id, date_from, date_to);

-- клетка графика: точка + дата + утро/вечер + роль → человек
CREATE TABLE schedule_slot (
    id         BIGSERIAL PRIMARY KEY,
    outlet_id  BIGINT      NOT NULL REFERENCES outlet (id) ON DELETE CASCADE,
    slot_date  DATE        NOT NULL,
    day_part   VARCHAR(10) NOT NULL,
    slot_role  VARCHAR(30) NOT NULL,
    user_id    BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    published  BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (outlet_id, slot_date, day_part, slot_role)
);
CREATE INDEX ix_slot_user_date ON schedule_slot (user_id, slot_date);

-- последняя выбранная штатка точки
CREATE TABLE outlet_staffing (
    outlet_id BIGINT       PRIMARY KEY REFERENCES outlet (id) ON DELETE CASCADE,
    morning   VARCHAR(100) NOT NULL DEFAULT 'INSIDE,SERVICE_MANAGER',
    evening   VARCHAR(100) NOT NULL DEFAULT 'INSIDE,SERVICE_MANAGER'
);