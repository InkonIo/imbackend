-- План из Excel «Прогноз продаж» и факт по сэндвичам из «10. Ежечасный сэндвич».
CREATE TABLE IF NOT EXISTS plan_day (
  branch_id  bigint  NOT NULL,
  day        date    NOT NULL,
  orders     int,                 -- план заказов (GC)
  revenue    numeric(14,2),       -- план выручки, тг
  avg_check  numeric(12,2),
  gcph       numeric(8,2),        -- «план GCPH»
  plan_hours numeric(8,2),        -- «план часы»
  plan_count numeric(8,2),        -- «план кол-во»
  PRIMARY KEY (branch_id, day)
);
CREATE TABLE IF NOT EXISTS plan_hour (
  branch_id bigint   NOT NULL,
  day       date     NOT NULL,
  hour      smallint NOT NULL,
  orders    int      NOT NULL DEFAULT 0,
  revenue   numeric(14,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (branch_id, day, hour)
);
-- план людей: MNG, ночь, длк, C, К, гель и т.д.
CREATE TABLE IF NOT EXISTS plan_staff (
  branch_id bigint NOT NULL,
  day       date   NOT NULL,
  label     text   NOT NULL,
  value     numeric(8,2),
  PRIMARY KEY (branch_id, day, label)
);
CREATE TABLE IF NOT EXISTS sandwich_hour (
  branch_id bigint   NOT NULL,
  day       date     NOT NULL,
  hour      smallint NOT NULL,
  qty       int      NOT NULL,
  PRIMARY KEY (branch_id, day, hour)
);