-- Шаблоны «приоритетов на день»: создают супер-админ и директор, менеджеры выбирают их в бланке.
-- outlet_id NULL = для всех точек (создаёт только супер-админ); иначе только для этой точки.
CREATE TABLE IF NOT EXISTS sheet_priority (
  id         bigserial PRIMARY KEY,
  title      varchar(200) NOT NULL CHECK (length(btrim(title)) > 0),
  category   varchar(40),
  outlet_id  bigint REFERENCES outlet(id) ON DELETE CASCADE,
  active     boolean NOT NULL DEFAULT true,
  sort_order int NOT NULL DEFAULT 0,
  created_by bigint REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_sheet_priority_outlet ON sheet_priority(outlet_id, active);

INSERT INTO sheet_priority (title, category, sort_order)
SELECT t.title, t.category, t.ord FROM (VALUES
  ('Повысить средний чек', 'Продажи', 1),
  ('Предлагать дополнительные позиции к каждому заказу', 'Продажи', 2),
  ('Увеличить долю киосков и мобильного приложения', 'Продажи', 3),
  ('Ускорить обслуживание гостей', 'Сервис', 4),
  ('Сократить ошибки в заказах', 'Сервис', 5),
  ('Улучшить качество общения с гостями', 'Сервис', 6),
  ('Чётко распределить участки и обязанности', 'Команда', 7),
  ('Контролировать стандарты и сроки хранения', 'Команда', 8),
  ('Улучшить коммуникацию на смене', 'Команда', 9)
) AS t(title, category, ord)
WHERE NOT EXISTS (SELECT 1 FROM sheet_priority);