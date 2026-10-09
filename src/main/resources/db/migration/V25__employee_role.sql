-- Роль «Сотрудник» (EMPLOYEE): самый низкий уровень, видит только своё.

-- 1) роль разрешена в CHECK
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS chk_account_role;
ALTER TABLE app_user ADD CONSTRAINT chk_account_role
    CHECK (account_role IN ('SUPER_ADMIN', 'DIRECTOR', 'MANAGER', 'EMPLOYEE'));

-- 2) связь аккаунта с карточкой сотрудника из Таймтрекера
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS ext_employee_id BIGINT REFERENCES ext_employee (id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_ext_employee ON app_user (ext_employee_id) WHERE ext_employee_id IS NOT NULL;
-- у EMPLOYEE связь обязательна: личные данные берутся только по ней
ALTER TABLE app_user ADD CONSTRAINT chk_employee_link
    CHECK (account_role <> 'EMPLOYEE' OR ext_employee_id IS NOT NULL);

-- 3) настройки (ответственный за расписание и т.п.)
CREATE TABLE IF NOT EXISTS ext_setting (
    key        VARCHAR(64) PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL
);

-- по умолчанию ответственная — Дильназ (супер-админ потом может сменить на сайте)
INSERT INTO ext_setting (key, value)
SELECT 'schedule_owner_user_id', id::text FROM app_user WHERE login = 'dilnaz'
ON CONFLICT (key) DO NOTHING;

-- 4) заявки сотрудников: выходной / недоступность в часы
CREATE TABLE emp_request (
    id            BIGSERIAL PRIMARY KEY,
    employee_id   BIGINT      NOT NULL REFERENCES ext_employee (id),
    kind          VARCHAR(16) NOT NULL CHECK (kind IN ('DAY_OFF', 'UNAVAILABLE')),
    date_from     DATE        NOT NULL,
    date_to       DATE        NOT NULL,
    time_from     TIME,
    time_to       TIME,
    comment       VARCHAR(300),
    status        VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    decided_by    BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    decided_at    TIMESTAMPTZ,
    decision_note VARCHAR(300),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (date_to >= date_from),
    CHECK ((kind = 'DAY_OFF' AND time_from IS NULL AND time_to IS NULL)
        OR (kind = 'UNAVAILABLE' AND time_from IS NOT NULL AND time_to IS NOT NULL AND time_to > time_from))
);
CREATE INDEX idx_emp_request_emp ON emp_request (employee_id, date_from);
CREATE INDEX idx_emp_request_status ON emp_request (status, date_from);
