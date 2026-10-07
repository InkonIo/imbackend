-- Данные из timetracker.kz (сотрудники, отделы, филиалы). Сырой JSON хранится в raw.
CREATE TABLE IF NOT EXISTS ext_branch (
  id bigint PRIMARY KEY, company_id bigint, title text NOT NULL, head_id bigint, synced_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS ext_department (
  id bigint PRIMARY KEY, company_id bigint, title text NOT NULL, work_days text, synced_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS ext_position (
  id bigint PRIMARY KEY, company_id bigint, title text NOT NULL, synced_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS ext_employee (
  id bigint PRIMARY KEY, company_id bigint, department_id bigint, branch_id bigint, position_id bigint,
  iin text, full_name text NOT NULL, firstname text, surname text, patronymic text, phone text,
  hired_date timestamptz, fired_date timestamptz, is_fired boolean NOT NULL DEFAULT false,
  is_registered boolean, raw jsonb, synced_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS ix_ext_employee_branch ON ext_employee(branch_id);
CREATE INDEX IF NOT EXISTS ix_ext_employee_name ON ext_employee(lower(full_name));
