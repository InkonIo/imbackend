-- Замечания директора по строке инвентаризации (кто ошибся в подсчёте → обратная связь).
CREATE TABLE IF NOT EXISTS inventory_feedback (
  id         bigserial PRIMARY KEY,
  count_id   bigint NOT NULL REFERENCES inventory_count(id) ON DELETE CASCADE,
  line_id    bigint NOT NULL REFERENCES inventory_line(id)  ON DELETE CASCADE,
  user_id    bigint NOT NULL REFERENCES app_user(id),   -- кому адресовано (кто считал)
  author_id  bigint NOT NULL REFERENCES app_user(id),   -- кто написал (директор / супер-админ)
  comment    text   NOT NULL CHECK (length(btrim(comment)) BETWEEN 1 AND 500),
  created_at timestamptz NOT NULL DEFAULT now(),
  seen_at    timestamptz
);
CREATE INDEX IF NOT EXISTS ix_inv_feedback_count ON inventory_feedback(count_id);
CREATE INDEX IF NOT EXISTS ix_inv_feedback_user  ON inventory_feedback(user_id, seen_at);