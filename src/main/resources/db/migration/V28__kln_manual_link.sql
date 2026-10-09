-- Ручная привязка сотрудника Таймтрекера к человеку из kln (когда телефоны не совпадают).
-- Переживает повторные синхронизации: kln_user.employee_id пересчитывается, а эта таблица остаётся.
CREATE TABLE IF NOT EXISTS kln_link (
    kln_user_id BIGINT      PRIMARY KEY,
    employee_id BIGINT      NOT NULL UNIQUE REFERENCES ext_employee (id) ON DELETE CASCADE,
    created_by  BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);