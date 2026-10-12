-- Импорт бумажного графика на неделю 2026-10-05 (только сотрудники, менеджеры не входят).
-- Источник: бумажный график «Расписание на день», сгенерирован 28.09.2026 17:34, 5 фото.
-- Запускать в psql / DBeaver целиком. Всё в одной транзакции: если кого-то не нашли в Таймтрекере, не запишется ничего,
-- а в сообщении будет список. Повторный запуск заменяет эту неделю (старая публикация уходит в архив).
-- Сначала должна быть применена миграция V32.

BEGIN;

CREATE FUNCTION pg_temp.nn(t text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT btrim(regexp_replace(lower(translate(t, 'әіңғүұқөһёйӘІҢҒҮҰҚӨҺЁЙ', 'аингуукохеиаингуукохеи')), '\s+', ' ', 'g'))
$$;

CREATE FUNCTION pg_temp.lev(a text, b text) RETURNS int LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE la int := length(a); lb int := length(b); prev int[]; cur int[]; i int; j int; c int;
BEGIN
  IF la = 0 THEN RETURN lb; END IF;
  IF lb = 0 THEN RETURN la; END IF;
  prev := ARRAY(SELECT generate_series(0, lb));
  FOR i IN 1..la LOOP
    cur := ARRAY[i];
    FOR j IN 1..lb LOOP
      c := CASE WHEN substr(a, i, 1) = substr(b, j, 1) THEN 0 ELSE 1 END;
      cur[j + 1] := least(prev[j + 1] + 1, cur[j] + 1, prev[j] + c);
    END LOOP;
    prev := cur;
  END LOOP;
  RETURN prev[lb + 1];
END $$;

-- Расстояние между словами: 0 — одинаково (с учётом казахских букв), 0.5 — одно слово — начало другого («Наухан» / «Науханова»), иначе число правок.
CREATE FUNCTION pg_temp.dist(a text, b text) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN a = b THEN 0
              WHEN least(length(a), length(b)) >= 5 AND (a LIKE b || '%' OR b LIKE a || '%') THEN 0.5
              ELSE pg_temp.lev(a, b) END
$$;

CREATE TEMP TABLE paper (emp text, day date, st time, en time, hours numeric, pos text, note text) ON COMMIT DROP;
INSERT INTO paper VALUES
  ('Абельдинова Адина', DATE '2026-10-05', TIME '18:00', TIME '00:00', 6, 'MR', NULL),
  ('Абельдинова Адина', DATE '2026-10-08', TIME '18:00', TIME '00:00', 6, 'K', NULL),
  ('Абельдинова Адина', DATE '2026-10-10', TIME '13:00', TIME '21:00', 8, 'MR', NULL),
  ('Абельдинова Адина', DATE '2026-10-11', TIME '18:00', TIME '00:00', 6, 'C', NULL),
  ('Нұрбеков Алихан', DATE '2026-10-05', TIME '16:00', TIME '00:00', 8, 'DLK', NULL),
  ('Нұрбеков Алихан', DATE '2026-10-07', TIME '13:00', TIME '22:00', 9, 'DLK', NULL),
  ('Нұрбеков Алихан', DATE '2026-10-09', TIME '15:00', TIME '00:00', 9, 'DLK', NULL),
  ('Нұрбеков Алихан', DATE '2026-10-11', TIME '17:00', TIME '00:00', 7, 'DLK', NULL),
  ('Каден Нұрай', DATE '2026-10-05', TIME '15:00', TIME '22:30', 7.5, 'C', NULL),
  ('Каден Нұрай', DATE '2026-10-06', TIME '14:00', TIME '21:00', 7, 'C', NULL),
  ('Каден Нұрай', DATE '2026-10-10', TIME '15:00', TIME '22:30', 7.5, 'C', NULL),
  ('Ораз Мөлдір', DATE '2026-10-05', TIME '08:00', TIME '16:00', 8, 'C', NULL),
  ('Ораз Мөлдір', DATE '2026-10-09', TIME '08:00', TIME '16:00', 8, 'C', NULL),
  ('Ораз Мөлдір', DATE '2026-10-10', TIME '09:00', TIME '15:00', 6, 'C', NULL),
  ('Нурберди Нәзерке', DATE '2026-10-05', TIME '16:00', TIME '00:00', 8, 'C', NULL),
  ('Нурберди Нәзерке', DATE '2026-10-10', TIME '16:00', TIME '00:00', 8, 'K', NULL),
  ('Нурберди Нәзерке', DATE '2026-10-11', TIME '16:00', TIME '00:00', 8, 'K', NULL),
  ('Құрманбек Айгерим', DATE '2026-10-05', TIME '14:00', TIME '21:00', 7, 'C', NULL),
  ('Құрманбек Айгерим', DATE '2026-10-08', TIME '08:00', TIME '12:00', 4, 'C', NULL),
  ('Құрманбек Айгерим', DATE '2026-10-10', TIME '12:00', TIME '20:00', 8, 'C', NULL),
  ('Ербол Әйгерім', DATE '2026-10-05', TIME '12:00', TIME '21:00', 9, 'K', NULL),
  ('Ербол Әйгерім', DATE '2026-10-07', TIME '16:00', TIME '00:00', 8, 'K', NULL),
  ('Ербол Әйгерім', DATE '2026-10-08', TIME '12:45', TIME '16:00', 3.25, 'TR', 'Касса, доп. обучение: Парахат Жанара'),
  ('Ербол Әйгерім', DATE '2026-10-08', TIME '16:00', TIME '20:00', 4, 'C', 'исправлено от руки'),
  ('Ербол Әйгерім', DATE '2026-10-09', TIME '08:00', TIME '16:00', 8, 'K', NULL),
  ('Мұқағали Мереке', DATE '2026-10-05', TIME '08:00', TIME '16:00', 8, 'K', NULL),
  ('Мұқағали Мереке', DATE '2026-10-06', TIME '08:00', TIME '13:00', 5, 'C', NULL),
  ('Мұқағали Мереке', DATE '2026-10-08', TIME '08:00', TIME '12:00', 4, 'K', NULL),
  ('Мұқағали Мереке', DATE '2026-10-10', TIME '08:00', TIME '13:00', 5, 'K', NULL),
  ('Мұқағали Мереке', DATE '2026-10-11', TIME '12:00', TIME '21:00', 9, 'GEL', NULL),
  ('Сыдық Жансая', DATE '2026-10-05', TIME '16:00', TIME '00:00', 8, 'K', NULL),
  ('Сыдық Жансая', DATE '2026-10-06', TIME '18:00', TIME '00:00', 6, 'C', NULL),
  ('Сыдық Жансая', DATE '2026-10-08', TIME '18:00', TIME '00:00', 6, 'C', NULL),
  ('Сыдық Жансая', DATE '2026-10-09', TIME '15:00', TIME '20:00', 5, 'C', NULL),
  ('Сыдық Жансая', DATE '2026-10-10', TIME '18:00', TIME '00:00', 6, 'C', NULL),
  ('Байдилда Диас', DATE '2026-10-05', TIME '23:30', TIME '08:30', 9, 'NT1', NULL),
  ('Байдилда Диас', DATE '2026-10-06', TIME '23:30', TIME '08:30', 9, 'NT1', NULL),
  ('Байдилда Диас', DATE '2026-10-08', TIME '23:30', TIME '08:30', 9, 'NT2', NULL),
  ('Байдилда Диас', DATE '2026-10-09', TIME '23:30', TIME '08:30', 9, 'NT1', NULL),
  ('Байдилда Диас', DATE '2026-10-11', TIME '23:30', TIME '08:30', 9, 'NT2', NULL),
  ('Ерзақ Бейбарыс', DATE '2026-10-05', TIME '23:30', TIME '08:30', 9, 'NT2', NULL),
  ('Ерзақ Бейбарыс', DATE '2026-10-07', TIME '23:30', TIME '08:30', 9, 'NT2', NULL),
  ('Ерзақ Бейбарыс', DATE '2026-10-08', TIME '23:30', TIME '08:30', 9, 'NT1', NULL),
  ('Ерзақ Бейбарыс', DATE '2026-10-10', TIME '23:30', TIME '08:30', 9, 'NT1', NULL),
  ('Ерзақ Бейбарыс', DATE '2026-10-11', TIME '23:30', TIME '08:30', 9, 'NT1', NULL),
  ('Абдіқалық Елдана', DATE '2026-10-06', TIME '14:00', TIME '22:30', 8.5, 'MR', NULL),
  ('Абдіқалық Елдана', DATE '2026-10-07', TIME '13:00', TIME '20:00', 7, 'MR', NULL),
  ('Абдіқалық Елдана', DATE '2026-10-09', TIME '17:00', TIME '21:30', 4.5, 'MR', NULL),
  ('Абдіқалық Елдана', DATE '2026-10-11', TIME '08:00', TIME '12:00', 4, 'K', NULL),
  ('Оралханов Икрам', DATE '2026-10-06', TIME '08:00', TIME '12:00', 4, 'DLK', NULL),
  ('Оралханов Икрам', DATE '2026-10-08', TIME '08:00', TIME '12:00', 4, 'DLK', NULL),
  ('Оралханов Икрам', DATE '2026-10-09', TIME '16:00', TIME '22:30', 6.5, 'C', NULL),
  ('Оралханов Икрам', DATE '2026-10-10', TIME '13:00', TIME '21:00', 8, 'C', NULL),
  ('Атчибай Бекзат', DATE '2026-10-06', TIME '16:00', TIME '00:30', 8.5, 'DLK', NULL),
  ('Атчибай Бекзат', DATE '2026-10-07', TIME '12:30', TIME '17:00', 4.5, 'K', NULL),
  ('Атчибай Бекзат', DATE '2026-10-10', TIME '15:00', TIME '22:30', 7.5, 'K', NULL),
  ('Атчибай Бекзат', DATE '2026-10-11', TIME '18:00', TIME '00:00', 6, 'K', NULL),
  ('Советова Жансая', DATE '2026-10-06', TIME '13:00', TIME '19:00', 6, 'C', NULL),
  ('Советова Жансая', DATE '2026-10-07', TIME '16:00', TIME '00:00', 8, 'C', NULL),
  ('Советова Жансая', DATE '2026-10-11', TIME '16:00', TIME '00:00', 8, 'C', NULL),
  ('Парахат Жанара', DATE '2026-10-06', TIME '12:00', TIME '18:00', 6, 'K', NULL),
  ('Парахат Жанара', DATE '2026-10-07', TIME '10:00', TIME '18:00', 8, 'C', NULL),
  ('Парахат Жанара', DATE '2026-10-08', TIME '12:45', TIME '16:00', 3.25, 'TRN', 'Касса, доп. обучение: инструктор Ербол Әйгерім'),
  ('Парахат Жанара', DATE '2026-10-08', TIME '16:00', TIME '19:00', 3, 'K', 'исправлено от руки'),
  ('Қожақова Сағыныш', DATE '2026-10-06', TIME '08:00', TIME '13:00', 5, 'K', NULL),
  ('Қожақова Сағыныш', DATE '2026-10-08', TIME '11:00', TIME '17:00', 6, 'K', NULL),
  ('Қожақова Сағыныш', DATE '2026-10-10', TIME '13:00', TIME '21:00', 8, 'GEL', NULL),
  ('Құрманәлі Жансая', DATE '2026-10-09', TIME '15:00', TIME '22:30', 7.5, 'K', NULL),
  ('Құрманәлі Жансая', DATE '2026-10-10', TIME '12:00', TIME '18:00', 6, 'K', NULL),
  ('Құрманәлі Жансая', DATE '2026-10-11', TIME '12:00', TIME '21:00', 9, 'K', NULL),
  ('Тәуекел Мейір', DATE '2026-10-10', TIME '09:00', TIME '17:00', 8, 'DLK', NULL),
  ('Тәуекел Мейір', DATE '2026-10-11', TIME '09:00', TIME '17:00', 8, 'DLK', NULL),
  ('Қалмұратов Мағжан', DATE '2026-10-06', TIME '18:00', TIME '00:00', 6, 'K', NULL),
  ('Қалмұратов Мағжан', DATE '2026-10-08', TIME '18:00', TIME '00:00', 6, 'DLK', NULL),
  ('Қалмұратов Мағжан', DATE '2026-10-10', TIME '17:00', TIME '00:00', 7, 'DLK', NULL),
  ('Шайхы Рамазан', DATE '2026-10-06', TIME '23:30', TIME '08:30', 9, 'NT2', NULL),
  ('Шайхы Рамазан', DATE '2026-10-07', TIME '23:30', TIME '08:30', 9, 'NT1', NULL),
  ('Шайхы Рамазан', DATE '2026-10-09', TIME '23:30', TIME '08:30', 9, 'NT2', NULL),
  ('Шайхы Рамазан', DATE '2026-10-10', TIME '23:30', TIME '08:30', 9, 'Combo', NULL),
  ('Сатыбалды Аруна', DATE '2026-10-07', TIME '12:00', TIME '19:00', 7, 'C', NULL),
  ('Сатыбалды Аруна', DATE '2026-10-08', TIME '23:00', TIME '00:00', 1, 'MR', NULL),
  ('Сатыбалды Аруна', DATE '2026-10-11', TIME '12:00', TIME '19:00', 7, 'C', NULL),
  ('Наухан Аяжан', DATE '2026-10-07', TIME '18:00', TIME '00:00', 6, 'C', NULL),
  ('Наухан Аяжан', DATE '2026-10-09', TIME '18:30', TIME '00:00', 5.5, 'C', NULL),
  ('Наухан Аяжан', DATE '2026-10-11', TIME '10:00', TIME '16:00', 6, 'C', NULL),
  ('Балтабекова Сабина', DATE '2026-10-07', TIME '08:00', TIME '13:00', 5, 'K', NULL),
  ('Балтабекова Сабина', DATE '2026-10-09', TIME '19:00', TIME '00:00', 5, 'K', NULL),
  ('Балтабекова Сабина', DATE '2026-10-11', TIME '11:00', TIME '17:00', 6, 'C', NULL),
  ('Қалабай Аяулым', DATE '2026-10-09', TIME '13:00', TIME '21:00', 8, 'K', NULL),
  ('Қалабай Аяулым', DATE '2026-10-10', TIME '13:00', TIME '21:00', 8, 'K', NULL),
  ('Қалабай Аяулым', DATE '2026-10-11', TIME '14:00', TIME '22:30', 8.5, 'K', NULL);

-- Если на бумаге имя написано иначе, чем в Таймтрекере, добавьте строку: ('как на бумаге', 'как в Таймтрекере (фамилия имя)').
CREATE TEMP TABLE alias (paper_name text, tt_name text) ON COMMIT DROP;
-- INSERT INTO alias VALUES ('Қалабай Аяулым', 'Қалбай Аяулым');

DO $$
DECLARE
  v_week date := DATE '2026-10-05';
  v_branch bigint;
  v_draft bigint;
  r record;
  n_shifts int;
  n_emps int;
  bad text := '';
BEGIN
  SELECT id INTO v_branch FROM ext_branch WHERE title ILIKE '%Достык%' ORDER BY id LIMIT 1;
  IF v_branch IS NULL THEN RAISE EXCEPTION 'Не найден ресторан по названию «%%Достык%%» в ext_branch'; END IF;

  CREATE TEMP TABLE m ON COMMIT DROP AS
  WITH names AS (
    SELECT DISTINCT p.emp, coalesce(a.tt_name, p.emp) AS src FROM paper p LEFT JOIN alias a ON a.paper_name = p.emp
  ), want AS (
    SELECT emp, split_part(pg_temp.nn(src), ' ', 1) AS s1, split_part(pg_temp.nn(src), ' ', 2) AS s2 FROM names
  ), cand AS (
    SELECT w.emp, e.id, e.full_name, e.is_fired,
           pg_temp.dist(w.s1, split_part(pg_temp.nn(e.full_name), ' ', 1)) + pg_temp.dist(w.s2, split_part(pg_temp.nn(e.full_name), ' ', 2)) AS score
    FROM want w JOIN ext_employee e ON e.branch_id = v_branch
  ), ranked AS (
    SELECT c.*, rank() OVER (PARTITION BY emp ORDER BY score, is_fired) AS rk FROM cand c
  )
  SELECT q.emp, q.id AS employee_id, q.full_name, q.score,
         (SELECT count(*) FROM ranked x WHERE x.emp = q.emp AND x.score = q.score AND x.is_fired = q.is_fired) AS ties
  FROM ranked q WHERE q.rk = 1;

  FOR r IN SELECT n.emp FROM (SELECT DISTINCT emp FROM paper) n
           WHERE NOT EXISTS (SELECT 1 FROM m WHERE m.emp = n.emp AND m.score <= 1 AND m.ties = 1) LOOP
    bad := bad || E'\n  • ' || r.emp || ' — ' || coalesce((SELECT string_agg(full_name || ' (отличий: ' || score || ')', '; ') FROM m WHERE m.emp = r.emp), 'нет похожих в ресторане');
  END LOOP;
  IF bad <> '' THEN
    RAISE EXCEPTION E'Не нашёл в Таймтрекере (или нашёл неоднозначно):%\nДобавьте строки в таблицу alias выше и запустите снова. Ничего не записано.', bad;
  END IF;

  FOR r IN SELECT emp, full_name, score FROM m WHERE score > 0 LOOP
    RAISE NOTICE 'Совпало неточно: «%» → «%»', r.emp, r.full_name;
  END LOOP;

  UPDATE plan_draft SET status = 'ARCHIVED' WHERE branch_id = v_branch AND week_start = v_week AND status = 'PUBLISHED';
  INSERT INTO plan_draft (branch_id, week_start, status, published_at) VALUES (v_branch, v_week, 'PUBLISHED', now()) RETURNING id INTO v_draft;

  INSERT INTO plan_draft_shift (draft_id, day, position_code, part, start_time, end_time, employee_id, manual, note)
  SELECT v_draft, p.day, p.pos,
         CASE WHEN extract(hour FROM p.st) * 60 + extract(minute FROM p.st) >= 1260
                OR extract(hour FROM p.st) * 60 + extract(minute FROM p.st) < 240 THEN 'NIGHT'
              WHEN extract(hour FROM p.st) * 60 + extract(minute FROM p.st) < 660 THEN 'MORNING'
              WHEN extract(hour FROM p.st) * 60 + extract(minute FROM p.st) < 900 THEN 'MID'
              ELSE 'EVENING' END,
         p.st, CASE WHEN p.en = TIME '00:00' THEN TIME '23:59' ELSE p.en END, m.employee_id, false, p.note
  FROM paper p JOIN m ON m.emp = p.emp
  ORDER BY p.day, p.st, p.emp;

  SELECT count(*), count(DISTINCT employee_id) INTO n_shifts, n_emps FROM plan_draft_shift WHERE draft_id = v_draft;
  RAISE NOTICE 'Готово: неделя %, смен %, сотрудников %, ресторан %', v_week, n_shifts, n_emps, v_branch;
END $$;

COMMIT;