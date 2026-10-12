-- «Табличка дня»: заметки точки (сломалась лампа, починили гриль…) со статусом и ответственным.
CREATE TABLE IF NOT EXISTS outlet_note (
  id             bigserial PRIMARY KEY,
  outlet_id      bigint NOT NULL REFERENCES outlet(id) ON DELETE CASCADE,
  title          varchar(200) NOT NULL CHECK (length(btrim(title)) > 0),
  body           text,
  status         varchar(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE')),
  created_by     bigint NOT NULL REFERENCES app_user(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  responsible_id bigint REFERENCES app_user(id),   -- кто взял в работу / закрыл
  status_by      bigint REFERENCES app_user(id),
  status_at      timestamptz,
  closed_at      timestamptz,
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_outlet_note_outlet ON outlet_note(outlet_id, status, created_at DESC);

-- история: кто и когда сменил статус, с комментарием («заменили лампу»)
CREATE TABLE IF NOT EXISTS outlet_note_log (
  id       bigserial PRIMARY KEY,
  note_id  bigint NOT NULL REFERENCES outlet_note(id) ON DELETE CASCADE,
  user_id  bigint NOT NULL REFERENCES app_user(id),
  status   varchar(12) NOT NULL,
  comment  varchar(500),
  at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_outlet_note_log_note ON outlet_note_log(note_id, at);