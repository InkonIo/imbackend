-- Workflow директора: отметки «сделано» по периодам (день, неделя, месяц, квартал, полгода, год).
-- Отметки личные: у каждого директора и у супер-админа свои.
CREATE TABLE IF NOT EXISTS wf_done (
  user_id    bigint      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  item_id    varchar(64) NOT NULL,
  period_key varchar(24) NOT NULL,
  done_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, item_id, period_key)
);