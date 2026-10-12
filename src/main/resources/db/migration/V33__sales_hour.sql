-- Почасовая выручка и гости из отчёта инсайда («1. Почасовая выручка»). Нужны для GCPH: гости за час на одного сотрудника.
CREATE TABLE IF NOT EXISTS sales_import (
  id          bigserial PRIMARY KEY,
  branch_id   bigint NOT NULL,
  file_name   text,
  restaurant  text,
  from_day    date,
  to_day      date,
  rows        int NOT NULL,
  guests      int NOT NULL,
  sales       numeric(16,2) NOT NULL DEFAULT 0,
  imported_at timestamptz NOT NULL DEFAULT now(),
  imported_by bigint
);

CREATE TABLE IF NOT EXISTS sales_hour (
  branch_id  bigint   NOT NULL,
  day        date     NOT NULL,             -- учётный день из отчёта
  hour       smallint NOT NULL CHECK (hour BETWEEN 0 AND 23),   -- «Час закрытия»: 12 = 12:00–12:59
  guests     int      NOT NULL,             -- GC, количество гостей
  sales      numeric(14,2) NOT NULL DEFAULT 0,   -- сумма без скидки, тг
  avg_check  numeric(12,2),                 -- средняя сумма заказа, тг
  import_id  bigint,
  PRIMARY KEY (branch_id, day, hour)
);
CREATE INDEX IF NOT EXISTS ix_sales_hour_day ON sales_hour (branch_id, day);