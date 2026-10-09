-- Автосборка графика сотрудников: позиции, данные из kln, нормы по ресторанам, черновики.

-- Справочник позиций. planned = участвует в автосборке (остальные только хранятся).
CREATE TABLE IF NOT EXISTS sched_position (
    code    VARCHAR(16) PRIMARY KEY,
    title   VARCHAR(80) NOT NULL,
    sort    INT         NOT NULL DEFAULT 100,
    planned BOOLEAN     NOT NULL DEFAULT FALSE,
    note    VARCHAR(200)
);
INSERT INTO sched_position (code, title, sort, planned, note) VALUES
    ('K',     'Кухня',                  10, TRUE,  NULL),
    ('DLK',   'Грузчик (тяжёлые коробки)', 20, TRUE, 'Помогает кухне с 16:00'),
    ('SUP',   'Саппорт кухни',          30, TRUE,  NULL),
    ('C',     'Прилавок',               40, TRUE,  NULL),
    ('MR',    'Манирум',                50, TRUE,  'Пока никого не обучили'),
    ('DLV',   'Доставка',               60, TRUE,  'Пока не актуально'),
    ('GEL',   'Гость',                  70, TRUE,  'Пока не актуально'),
    ('NT1',   'Ночь 1',                 80, TRUE,  '23:30–08:30, мойка ресторана'),
    ('NT2',   'Ночь 2',                 81, TRUE,  '23:30–08:30, мойка ресторана'),
    ('TR',    'Инструктор',             90, TRUE,  'Ставится вручную на период'),
    ('TRN',   'Стажёр',                 91, TRUE,  'Ставится вручную на период'),
    ('Combo', 'Combo',                 100, FALSE, 'Значение уточняется'),
    ('NT3',   'Ночь 3',                 82, FALSE, NULL),
    ('NT4',   'Ночь 4',                 83, FALSE, NULL),
    ('KN',    'KN',                    110, FALSE, NULL),
    ('FF',    'FF',                    111, FALSE, NULL),
    ('DT',    'DT',                    112, FALSE, NULL),
    ('DTN',   'DTN',                   113, FALSE, NULL),
    ('DEL',   'DEL',                   114, FALSE, NULL),
    ('REG',   'REG',                   115, FALSE, NULL),
    ('MNGR',  'Менеджер',              116, FALSE, 'Менеджеры ходят по своему графику'),
    ('PP',    'PP',                    117, FALSE, NULL),
    ('match', 'match',                 118, FALSE, NULL)
ON CONFLICT (code) DO NOTHING;

-- Рестораны kln (id в kln НЕ совпадает с id филиала в Таймтрекере, связь задаётся вручную).
CREATE TABLE IF NOT EXISTS kln_branch (
    id              BIGINT PRIMARY KEY,
    title           TEXT        NOT NULL,
    schedule_fields JSONB       NOT NULL DEFAULT '[]'::jsonb,
    fixed_shifts    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    tt_branch_id    BIGINT REFERENCES ext_branch (id) ON DELETE SET NULL,
    synced_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Сотрудники kln. ИИН, почту и токены НЕ храним. phone_key = последние 10 цифр телефона.
CREATE TABLE IF NOT EXISTS kln_user (
    id          BIGINT PRIMARY KEY,
    branch_id   BIGINT,
    full_name   TEXT        NOT NULL,
    phone_key   VARCHAR(10),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    ignore_sync BOOLEAN     NOT NULL DEFAULT FALSE,
    accesses    TEXT[]      NOT NULL DEFAULT '{}',
    possibles   TEXT[]      NOT NULL DEFAULT '{}',
    employee_id BIGINT REFERENCES ext_employee (id) ON DELETE SET NULL,
    synced_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_kln_user_phone ON kln_user (phone_key);
CREATE INDEX IF NOT EXISTS ix_kln_user_emp ON kln_user (employee_id);

-- На каких позициях может работать сотрудник. KLN — из kln (перезаписывается при синхронизации), MANUAL — руками.
CREATE TABLE IF NOT EXISTS emp_position (
    employee_id   BIGINT      NOT NULL REFERENCES ext_employee (id) ON DELETE CASCADE,
    position_code VARCHAR(16) NOT NULL REFERENCES sched_position (code),
    source        VARCHAR(8)  NOT NULL DEFAULT 'KLN' CHECK (source IN ('KLN', 'MANUAL')),
    PRIMARY KEY (employee_id, position_code)
);

-- Нормы по ресторану: сколько человек какой позиции нужно в какую часть дня и в какие часы.
-- Если end_time <= start_time, смена переходит через полночь. 23:59 считается полуночью.
CREATE TABLE IF NOT EXISTS plan_slot (
    branch_id     BIGINT      NOT NULL REFERENCES ext_branch (id) ON DELETE CASCADE,
    position_code VARCHAR(16) NOT NULL REFERENCES sched_position (code),
    part          VARCHAR(8)  NOT NULL CHECK (part IN ('MORNING', 'MID', 'EVENING', 'NIGHT')),
    need          INT         NOT NULL DEFAULT 1 CHECK (need BETWEEN 0 AND 20),
    start_time    TIME        NOT NULL,
    end_time      TIME        NOT NULL,
    PRIMARY KEY (branch_id, position_code, part)
);

-- Обучение: инструктор (TR) и стажёр (TRN) на период, с комментарием.
CREATE TABLE IF NOT EXISTS plan_training (
    id            BIGSERIAL PRIMARY KEY,
    branch_id     BIGINT NOT NULL REFERENCES ext_branch (id) ON DELETE CASCADE,
    instructor_id BIGINT NOT NULL REFERENCES ext_employee (id),
    trainee_id    BIGINT REFERENCES ext_employee (id),
    date_from     DATE   NOT NULL,
    date_to       DATE   NOT NULL,
    start_time    TIME   NOT NULL DEFAULT '08:00',
    end_time      TIME   NOT NULL DEFAULT '16:00',
    comment       VARCHAR(300),
    created_by    BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (date_to >= date_from)
);
CREATE INDEX IF NOT EXISTS ix_plan_training_dates ON plan_training (branch_id, date_from, date_to);

-- Черновики недели. Опубликованный остаётся для истории.
CREATE TABLE IF NOT EXISTS plan_draft (
    id           BIGSERIAL PRIMARY KEY,
    branch_id    BIGINT NOT NULL REFERENCES ext_branch (id) ON DELETE CASCADE,
    week_start   DATE   NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_by   BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    published_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_plan_draft_open ON plan_draft (branch_id, week_start) WHERE status = 'DRAFT';

-- Клетки черновика. employee_id IS NULL = слот не закрыт (красная пустая клетка).
CREATE TABLE IF NOT EXISTS plan_draft_shift (
    id            BIGSERIAL PRIMARY KEY,
    draft_id      BIGINT      NOT NULL REFERENCES plan_draft (id) ON DELETE CASCADE,
    day           DATE        NOT NULL,
    position_code VARCHAR(16) NOT NULL REFERENCES sched_position (code),
    part          VARCHAR(8)  NOT NULL,
    start_time    TIME        NOT NULL,
    end_time      TIME        NOT NULL,
    employee_id   BIGINT REFERENCES ext_employee (id),
    manual        BOOLEAN     NOT NULL DEFAULT FALSE,
    note          VARCHAR(300)
);
CREATE INDEX IF NOT EXISTS ix_plan_draft_shift ON plan_draft_shift (draft_id, day);

INSERT INTO ext_setting (key, value, updated_at) VALUES
    ('plan_max_consecutive', '6', now()),
    ('plan_min_rest_hours', '10', now())
ON CONFLICT (key) DO NOTHING;