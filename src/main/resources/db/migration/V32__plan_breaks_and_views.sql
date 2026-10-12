-- Перерывы и «непроизводственные часы». Правило взято из бумажного графика:
-- смена до 4 ч — без перерыва; от 4 ч — 30; от 5,5 ч — 15 + 30; от 7,5 ч — 15 + 15 + 30 (минут).
CREATE TABLE IF NOT EXISTS plan_break_rule (
  min_hours numeric(4,2) PRIMARY KEY,
  minutes   int[] NOT NULL
);
INSERT INTO plan_break_rule (min_hours, minutes) VALUES
  (0,   '{}'),
  (4,   '{30}'),
  (5.5, '{15,30}'),
  (7.5, '{15,15,30}')
ON CONFLICT (min_hours) DO NOTHING;

-- Сколько минут перерывов положено на смену длиной dur_min минут.
CREATE OR REPLACE FUNCTION plan_break_min(dur_min numeric) RETURNS int
LANGUAGE sql STABLE AS $$
  SELECT coalesce((
    SELECT sum(x)::int
    FROM (SELECT minutes FROM plan_break_rule WHERE min_hours * 60 <= dur_min ORDER BY min_hours DESC LIMIT 1) r,
         unnest(r.minutes) x), 0)
$$;

-- Смены опубликованных и черновых недель с длиной в минутах (23:59 = полночь, конец <= начала = переход через полночь).
CREATE OR REPLACE VIEW plan_shift_eff AS
SELECT s.id, d.branch_id, d.week_start, d.status, s.day, s.position_code, s.part, s.employee_id, s.note,
       s.start_time, s.end_time,
       CASE WHEN s.end_time = TIME '23:59' THEN 1440 - sm.v
            WHEN em.v <= sm.v THEN 1440 + em.v - sm.v
            ELSE em.v - sm.v END::int AS dur_min,
       sm.v AS start_min
FROM plan_draft_shift s
JOIN plan_draft d ON d.id = s.draft_id AND d.status IN ('PUBLISHED', 'DRAFT')
CROSS JOIN LATERAL (SELECT (extract(hour FROM s.start_time) * 60 + extract(minute FROM s.start_time))::int AS v) sm
CROSS JOIN LATERAL (SELECT (extract(hour FROM s.end_time) * 60 + extract(minute FROM s.end_time))::int AS v) em
WHERE s.employee_id IS NOT NULL;