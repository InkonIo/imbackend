-- =====================================================================
-- ДЕМО-ИСТОРИЯ для презентации: 30 дней смен Dostyk Plaza.
-- Наполняет: смены, маршруты (чек-листы) с пунктами, фото, комментарии, флаги,
-- проверки директора, метрики (аналитика, рейтинг), активность, инвентаризации,
-- уведомления и заметки.
-- Всё созданное записывается в demo_ids, чтобы удалить одним demo_cleanup.sql.
-- Реальные данные не трогаются.
-- Запускать целиком, один раз.
-- =====================================================================
CREATE TABLE IF NOT EXISTS demo_ids (
    kind text   NOT NULL,
    id   bigint NOT NULL,
    PRIMARY KEY (kind, id)
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM demo_ids) THEN
        RAISE EXCEPTION 'Демо уже загружено. Сначала выполни demo_cleanup.sql';
    END IF;
END $$;

SELECT setseed(0.2026);

-- 1. Аккаунты: включаем и снимаем «сменить пароль»
UPDATE app_user SET active = TRUE, must_change_password = FALSE
WHERE login IN ('almat','anar','aruzhan','saniya','zhanar','diana','aigerim','galiya',
                'bakytzhan','dilnaz','altynai','sherkhan','ladarina');

-- 2. Смены и маршруты
DO $$
DECLARE
    v_outlet bigint;
    v_dir    bigint;
    v_pool   bigint[];
    v_n      int;
    today    date := (now() AT TIME ZONE 'Asia/Almaty')::date;
    d        date;
    part     text;
    v_uid    bigint;
    v_login  text;
    v_rate   numeric;
    v_tpl    bigint;
    v_shift  bigint;
    v_run    bigint;
    t0       timestamptz;
    t1       timestamptz;
    used     bigint[];
    k        int;
    comments_problem text[] := ARRAY[
        'Не хватило расходников, написала в чат',
        'Оборудование не включилось, вызвали технолога',
        'Нет доступа, ключ у ночного менеджера',
        'Сломана тележка, заменили на запасную',
        'Не было поставки, пересчитали по складу'];
    comments_ok text[] := ARRAY[
        'Выполнено, замечаний нет',
        'Всё в норме',
        'Проверила вместе с кассиром',
        'Сделали до пика',
        'Ротация проведена, просрочки нет'];
BEGIN
    SELECT id INTO v_outlet FROM outlet WHERE name = 'Dostyk Plaza' ORDER BY id LIMIT 1;
    IF v_outlet IS NULL THEN RAISE EXCEPTION 'Не найдена точка Dostyk Plaza'; END IF;
    SELECT id INTO v_dir FROM app_user WHERE login = 'ladarina';

    SELECT array_agg(u.id ORDER BY u.id) INTO v_pool
    FROM app_user u
    JOIN staff_profile sp ON sp.user_id = u.id AND sp.can_inside
    JOIN user_outlet uo ON uo.user_id = u.id AND uo.outlet_id = v_outlet
    WHERE u.account_role = 'MANAGER' AND u.active;
    v_n := coalesce(array_length(v_pool, 1), 0);
    IF v_n < 2 THEN RAISE EXCEPTION 'Мало менеджеров с правом «инсайд» на точке'; END IF;

    FOR d IN SELECT g::date FROM generate_series(today - 30, today - 1, interval '1 day') g LOOP
        used := ARRAY[]::bigint[];
        FOREACH part IN ARRAY ARRAY['MORNING', 'EVENING'] LOOP
            SELECT id INTO v_tpl FROM checklist_template
            WHERE shift_role = 'INSIDE' AND day_part = part AND active AND outlet_id IS NULL
            ORDER BY id LIMIT 1;
            CONTINUE WHEN v_tpl IS NULL;

            -- человек: случайный, но не двое одинаковых в один день
            LOOP
                v_uid := v_pool[1 + floor(random() * v_n)::int];
                EXIT WHEN NOT (v_uid = ANY (used));
            END LOOP;
            used := used || v_uid;
            SELECT login INTO v_login FROM app_user WHERE id = v_uid;
            v_rate := CASE v_login
                WHEN 'anar' THEN 0.94 WHEN 'diana' THEN 0.91 WHEN 'altynai' THEN 0.89
                WHEN 'aruzhan' THEN 0.86 WHEN 'aigerim' THEN 0.80 WHEN 'galiya' THEN 0.72
                WHEN 'dilnaz' THEN 0.62 ELSE 0.80 END;
            v_rate := least(0.98, greatest(0.4, v_rate + (random() - 0.5) * 0.10));

            t0 := ((d + CASE part WHEN 'MORNING' THEN time '08:00' ELSE time '16:00' END)::timestamp
                   AT TIME ZONE 'Asia/Almaty') + (random() * 12 - 4) * interval '1 minute';
            t1 := t0 + (500 + random() * 25) * interval '1 minute';

            INSERT INTO shift_session (user_id, outlet_id, shift_role, day_part, shift_date, started_at, finished_at)
            VALUES (v_uid, v_outlet, 'INSIDE', part, d, t0, t1) RETURNING id INTO v_shift;
            INSERT INTO demo_ids VALUES ('shift', v_shift);

            INSERT INTO checklist_run (shift_id, template_id) VALUES (v_shift, v_tpl) RETURNING id INTO v_run;

            INSERT INTO checklist_run_item (run_id, item_id, section_order, section_title, sort_order, title,
                duration_min, due_from, due_to, photo_mode, director_review, instructions, telegram_notify, action, status)
            SELECT v_run, i.id, s.sort_order, s.title, i.sort_order, i.title,
                   i.duration_min, i.due_from, i.due_to, i.photo_mode, i.director_review, i.instructions,
                   i.telegram_notify, i.action, 'PENDING'
            FROM checklist_item i JOIN checklist_section s ON s.id = i.section_id
            WHERE s.template_id = v_tpl AND i.active
              AND (i.weekday IS NULL OR i.weekday = extract(isodow FROM d)::int);

            -- статусы
            UPDATE checklist_run_item ri SET status = CASE
                    WHEN x.r < v_rate THEN 'DONE'
                    WHEN x.r < v_rate + 0.03 THEN 'PROBLEM'
                    WHEN x.r < v_rate + 0.07 THEN 'SKIPPED'
                    ELSE 'PENDING' END
            FROM (SELECT id, random() AS r FROM checklist_run_item WHERE run_id = v_run) x
            WHERE ri.id = x.id;

            -- время выполнения: пункты со сроком — около срока, остальные равномерно по смене
            UPDATE checklist_run_item ri SET done_at = CASE
                    WHEN ri.due_to IS NOT NULL THEN
                        ((d + ri.due_to)::timestamp AT TIME ZONE 'Asia/Almaty')
                        + CASE WHEN ri.due_to < time '06:00' THEN interval '1 day' ELSE interval '0' END
                        + CASE WHEN x.r2 < 0.94 THEN -(x.r3 * 25) ELSE (5 + x.r3 * 40) END * interval '1 minute'
                    ELSE t0 + ((x.rn - 1)::numeric / x.cnt) * ((t1 - t0) - interval '40 minutes')
                         + (x.r3 * 8) * interval '1 minute'
                END
            FROM (SELECT id, random() AS r2, random() AS r3,
                         row_number() OVER (ORDER BY section_order, sort_order) AS rn,
                         count(*) OVER () AS cnt
                  FROM checklist_run_item WHERE run_id = v_run) x
            WHERE ri.id = x.id AND ri.status IN ('DONE', 'PROBLEM');

            UPDATE checklist_run_item SET done_at = greatest(done_at, t0 + interval '5 minutes')
            WHERE run_id = v_run AND done_at IS NOT NULL;

            -- когда начал: за длительность пункта до конца; у ~4% — «слишком быстро»
            UPDATE checklist_run_item ri SET started_at = ri.done_at -
                    CASE WHEN x.r < 0.04 AND coalesce(ri.duration_min, 0) >= 5 THEN interval '15 seconds'
                         ELSE coalesce(ri.duration_min, 2 + x.r * 5) * interval '1 minute' * (0.9 + x.r * 0.3) END
            FROM (SELECT id, random() AS r FROM checklist_run_item WHERE run_id = v_run) x
            WHERE ri.id = x.id AND ri.done_at IS NOT NULL;

            -- комментарии
            UPDATE checklist_run_item ri SET comment = CASE
                    WHEN ri.status = 'PROBLEM' THEN comments_problem[1 + floor(x.r * array_length(comments_problem, 1))::int]
                    ELSE comments_ok[1 + floor(x.r * array_length(comments_ok, 1))::int] END
            FROM (SELECT id, random() AS r FROM checklist_run_item WHERE run_id = v_run) x
            WHERE ri.id = x.id AND ((ri.status = 'PROBLEM') OR (ri.status = 'DONE' AND x.r < 0.06));

            -- смена закончилась не раньше последнего пункта
            UPDATE shift_session s SET finished_at = greatest(s.finished_at,
                   (SELECT max(done_at) FROM checklist_run_item WHERE run_id = v_run) + interval '4 minutes')
            WHERE s.id = v_shift;

            -- фото на пунктах, где они нужны
            INSERT INTO checklist_photo (run_item_id, file_path, uploaded_at, sha256, taken_at)
            SELECT ri.id, 'demo/photo_demo.jpg', ri.done_at,
                   md5(ri.id::text || 'a') || md5(ri.id::text || 'b'),
                   ri.done_at - interval '40 seconds'
            FROM checklist_run_item ri
            WHERE ri.run_id = v_run AND ri.status = 'DONE' AND ri.photo_mode = 'REQUIRED';

            INSERT INTO checklist_photo (run_item_id, file_path, uploaded_at, sha256, taken_at)
            SELECT ri.id, 'demo/photo_demo.jpg', ri.done_at,
                   md5(ri.id::text || 'c') || md5(ri.id::text || 'd'),
                   ri.done_at - interval '40 seconds'
            FROM checklist_run_item ri
            WHERE ri.run_id = v_run AND ri.status = 'PROBLEM' AND ri.photo_mode IN ('REQUIRED', 'ON_PROBLEM');

            -- активность: человек «в системе» большую часть смены
            INSERT INTO activity_minute (user_id, minute, shift_id, device_id)
            SELECT v_uid, g, v_shift, 'demo-' || v_uid
            FROM generate_series(date_trunc('minute', t0), date_trunc('minute', t1), interval '1 minute') g
            WHERE random() < (0.50 + v_rate * 0.30)
            ON CONFLICT DO NOTHING;
        END LOOP;
    END LOOP;
END $$;

-- 3. Фото: у нескольких снимок «старый» или повторный
UPDATE checklist_photo p SET taken_at = p.uploaded_at - interval '2 days'
WHERE p.id IN (
    SELECT p2.id FROM checklist_photo p2
    JOIN checklist_run_item ri ON ri.id = p2.run_item_id
    JOIN checklist_run r ON r.id = ri.run_id
    JOIN demo_ids di ON di.kind = 'shift' AND di.id = r.shift_id
    WHERE random() < 0.012);

-- 4. Проверка директора по пунктам со «звёздочкой»
INSERT INTO item_review (run_item_id, shift_id, outlet_id, decision, comment, reviewer_id, reviewer_login, reviewed_at)
SELECT ri.id, s.id, s.outlet_id,
       CASE WHEN x.r < 0.85 THEN 'APPROVED' ELSE 'REJECTED' END,
       CASE WHEN x.r < 0.85 THEN (ARRAY[NULL, 'Ок', 'Хорошо', NULL])[1 + floor(x.r2 * 4)::int]
            ELSE (ARRAY['Нужно фото крупнее', 'Не видно ножки тележек', 'Переделать, порядок нарушен'])[1 + floor(x.r2 * 3)::int] END,
       dr.id, dr.login, s.finished_at + (1 + x.r2 * 10) * interval '1 hour'
FROM checklist_run_item ri
JOIN checklist_run r ON r.id = ri.run_id
JOIN shift_session s ON s.id = r.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
JOIN app_user dr ON dr.login = 'ladarina'
CROSS JOIN LATERAL (SELECT random() AS r, random() AS r2) x
WHERE ri.director_review AND ri.status = 'DONE'
  AND s.shift_date < (now() AT TIME ZONE 'Asia/Almaty')::date - 1;

-- 5. Флаги
-- 5.1 «не сделано» и «пропуск» — по самим пунктам
INSERT INTO audit_flag (created_at, shift_id, run_item_id, user_id, outlet_id, type, severity, details, review_status)
SELECT s.finished_at, s.id, ri.id, s.user_id, s.outlet_id,
       CASE ri.status WHEN 'SKIPPED' THEN 'SKIPPED' ELSE 'NOT_DONE' END, 'LOW',
       left(ri.title, 200), 'DISMISSED'
FROM checklist_run_item ri
JOIN checklist_run r ON r.id = ri.run_id
JOIN shift_session s ON s.id = r.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
WHERE ri.status IN ('PENDING', 'SKIPPED');

-- 5.2 опоздание: сделан позже срока
INSERT INTO audit_flag (created_at, shift_id, run_item_id, user_id, outlet_id, type, severity, details, review_status)
SELECT ri.done_at, s.id, ri.id, s.user_id, s.outlet_id, 'LATE', 'MEDIUM',
       'Срок до ' || to_char(ri.due_to, 'HH24:MI') || ', закрыто ' ||
       to_char(ri.done_at AT TIME ZONE 'Asia/Almaty', 'HH24:MI'), 'OPEN'
FROM checklist_run_item ri
JOIN checklist_run r ON r.id = ri.run_id
JOIN shift_session s ON s.id = r.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
WHERE ri.status IN ('DONE', 'PROBLEM') AND ri.due_to IS NOT NULL
  AND ri.done_at > ((s.shift_date + ri.due_to)::timestamp AT TIME ZONE 'Asia/Almaty')
        + CASE WHEN ri.due_to < time '06:00' THEN interval '1 day' ELSE interval '0' END;

-- 5.3 слишком быстро
INSERT INTO audit_flag (created_at, shift_id, run_item_id, user_id, outlet_id, type, severity, details, review_status)
SELECT ri.done_at, s.id, ri.id, s.user_id, s.outlet_id, 'TOO_FAST', 'MEDIUM',
       'Норма ' || ri.duration_min || ' мин, сделано за ' ||
       greatest(1, round(extract(epoch FROM (ri.done_at - ri.started_at)) / 60.0)::int) || ' мин', 'OPEN'
FROM checklist_run_item ri
JOIN checklist_run r ON r.id = ri.run_id
JOIN shift_session s ON s.id = r.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
WHERE ri.status = 'DONE' AND coalesce(ri.duration_min, 0) >= 5
  AND ri.done_at - ri.started_at < interval '1 minute';

-- 5.4 старое фото
INSERT INTO audit_flag (created_at, shift_id, run_item_id, photo_id, user_id, outlet_id, type, severity, details, review_status)
SELECT ri.done_at, s.id, ri.id, p.id, s.user_id, s.outlet_id, 'OLD_PHOTO', 'HIGH',
       'Фото сделано за 2 дня до отметки', 'OPEN'
FROM checklist_photo p
JOIN checklist_run_item ri ON ri.id = p.run_item_id
JOIN checklist_run r ON r.id = ri.run_id
JOIN shift_session s ON s.id = r.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
WHERE p.taken_at < p.uploaded_at - interval '1 day';

-- 5.5 отклонено директором
INSERT INTO audit_flag (created_at, shift_id, run_item_id, user_id, outlet_id, type, severity, details, review_status, reviewed_by, reviewed_at, review_comment)
SELECT rv.reviewed_at, s.id, ri.id, s.user_id, s.outlet_id, 'REJECTED', 'MEDIUM',
       left(ri.title, 200), 'CONFIRMED', rv.reviewer_id, rv.reviewed_at, rv.comment
FROM item_review rv
JOIN checklist_run_item ri ON ri.id = rv.run_item_id
JOIN shift_session s ON s.id = rv.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
WHERE rv.decision = 'REJECTED';

-- 5.6 по смене: пачка отметок, смена устройства, долгая пауза, медленно
INSERT INTO audit_flag (created_at, shift_id, user_id, outlet_id, type, severity, details, review_status)
SELECT s.finished_at - interval '30 minutes', s.id, s.user_id, s.outlet_id, t.type, t.sev, t.details,
       CASE WHEN t.sev = 'LOW' THEN 'DISMISSED' ELSE 'OPEN' END
FROM shift_session s
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
CROSS JOIN LATERAL (
    SELECT * FROM (VALUES
        ('BURST',         'MEDIUM', 'Много пунктов отмечено за минуту в конце смены', 0.07),
        ('DEVICE_SWITCH', 'LOW',    'Отметки с двух устройств за смену',              0.10),
        ('IDLE_LONG',     'LOW',    'Пауза в работе с системой больше 40 минут',      0.22),
        ('SLOW',          'LOW',    'Пункты закрывались заметно дольше нормы',        0.12)
    ) v (type, sev, details, p) WHERE random() + s.id * 0 < v.p) t;

-- 5.7 решение директора по флагам
-- старше двух дней: часть подтверждена, часть отклонена; свежие остаются «ждут проверки»
UPDATE audit_flag f SET
    review_status = CASE WHEN x.r < 0.55 THEN 'CONFIRMED' ELSE 'DISMISSED' END,
    reviewed_by = dr.id,
    reviewed_at = f.created_at + (3 + x.r2 * 20) * interval '1 hour',
    review_comment = CASE WHEN x.r < 0.55 THEN (ARRAY['Принято к сведению', 'Обсудили со сменой', 'Замечание записано'])[1 + floor(x.r2 * 3)::int]
                          ELSE (ARRAY['Причина объективная', 'Проверил, нарушения нет'])[1 + floor(x.r2 * 2)::int] END
FROM (SELECT id, random() AS r, random() AS r2 FROM audit_flag) x, app_user dr
WHERE f.id = x.id AND dr.login = 'ladarina'
  AND f.review_status = 'OPEN' AND f.type <> 'REJECTED'
  AND f.shift_id IN (SELECT id FROM demo_ids WHERE kind = 'shift')
  AND f.created_at < now() - interval '2 days';

INSERT INTO demo_ids SELECT 'flag', f.id FROM audit_flag f
WHERE f.shift_id IN (SELECT id FROM demo_ids WHERE kind = 'shift');

-- 6. Метрики смен (отсюда берут аналитика и рейтинг)
INSERT INTO shift_metrics (shift_id, user_id, outlet_id, shift_date, day_part, shift_role, started_at, finished_at,
    duration_min, active_min, items_total, items_done, items_problem, items_skipped, items_not_done, completion_pct,
    due_total, due_on_time, on_time_pct, photos_total, comments_total, flags_total, flags_high, flags_open,
    flags_confirmed, flags_dismissed, too_fast, late, items_approved, items_rejected, score,
    confirmed_high, confirmed_medium)
SELECT a.shift_id, a.user_id, a.outlet_id, a.shift_date, a.day_part, a.shift_role, a.started_at, a.finished_at,
       a.duration_min, a.active_min, a.items_total, a.items_done, a.items_problem, a.items_skipped, a.items_not_done,
       CASE WHEN a.items_total > 0 THEN round(100.0 * (a.items_done + a.items_problem) / a.items_total) ELSE 0 END,
       a.due_total, a.due_on_time,
       CASE WHEN a.due_total > 0 THEN round(100.0 * a.due_on_time / a.due_total) END,
       a.photos_total, a.comments_total, a.flags_total, a.flags_high, a.flags_open,
       a.flags_confirmed, a.flags_dismissed, a.too_fast, a.late, a.items_approved, a.items_rejected,
       a.items_done + a.items_problem + a.due_on_time + 2 * a.items_approved - a.items_skipped
         - 2 * a.items_not_done - 3 * a.confirmed_medium - 5 * a.confirmed_high
         + CASE WHEN a.items_done + a.items_problem = a.items_total AND a.confirmed_high + a.confirmed_medium = 0
                     AND a.items_rejected = 0 THEN 10 ELSE 0 END,
       a.confirmed_high, a.confirmed_medium
FROM (
    SELECT s.id AS shift_id, s.user_id, s.outlet_id, s.shift_date, s.day_part, s.shift_role, s.started_at, s.finished_at,
           round(extract(epoch FROM (s.finished_at - s.started_at)) / 60.0)::int AS duration_min,
           (SELECT count(*) FROM activity_minute am WHERE am.shift_id = s.id)::int AS active_min,
           count(ri.id)::int AS items_total,
           (count(*) FILTER (WHERE ri.status = 'DONE'))::int AS items_done,
           (count(*) FILTER (WHERE ri.status = 'PROBLEM'))::int AS items_problem,
           (count(*) FILTER (WHERE ri.status = 'SKIPPED'))::int AS items_skipped,
           (count(*) FILTER (WHERE ri.status = 'PENDING'))::int AS items_not_done,
           (count(*) FILTER (WHERE ri.due_to IS NOT NULL))::int AS due_total,
           (count(*) FILTER (WHERE ri.due_to IS NOT NULL AND ri.status IN ('DONE', 'PROBLEM') AND ri.done_at <=
                ((s.shift_date + ri.due_to)::timestamp AT TIME ZONE 'Asia/Almaty')
                + CASE WHEN ri.due_to < time '06:00' THEN interval '1 day' ELSE interval '0' END))::int AS due_on_time,
           (SELECT count(*) FROM checklist_photo p JOIN checklist_run_item i2 ON i2.id = p.run_item_id
             JOIN checklist_run r2 ON r2.id = i2.run_id WHERE r2.shift_id = s.id)::int AS photos_total,
           (count(*) FILTER (WHERE ri.comment IS NOT NULL))::int AS comments_total,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id)::int AS flags_total,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.severity = 'HIGH')::int AS flags_high,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.review_status = 'OPEN')::int AS flags_open,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.review_status = 'CONFIRMED')::int AS flags_confirmed,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.review_status = 'DISMISSED')::int AS flags_dismissed,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.type = 'TOO_FAST')::int AS too_fast,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.type = 'LATE')::int AS late,
           (SELECT count(*) FROM item_review v WHERE v.shift_id = s.id AND v.decision = 'APPROVED')::int AS items_approved,
           (SELECT count(*) FROM item_review v WHERE v.shift_id = s.id AND v.decision = 'REJECTED')::int AS items_rejected,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.review_status = 'CONFIRMED' AND f.severity = 'HIGH')::int AS confirmed_high,
           (SELECT count(*) FROM audit_flag f WHERE f.shift_id = s.id AND f.review_status = 'CONFIRMED' AND f.severity = 'MEDIUM')::int AS confirmed_medium
    FROM shift_session s
    JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
    JOIN checklist_run r ON r.shift_id = s.id
    JOIN checklist_run_item ri ON ri.run_id = r.id
    GROUP BY s.id
) a;

-- 7. Уведомления сотрудникам: отклонённые пункты и подтверждённые флаги
INSERT INTO user_notification (user_id, type, title, body, shift_id, run_item_id, created_by, created_at, read_at)
SELECT s.user_id, 'REVIEW', 'Директор вернул пункт на доработку',
       left(ri.title, 200) || coalesce(': ' || rv.comment, ''), s.id, ri.id, rv.reviewer_id, rv.reviewed_at,
       CASE WHEN rv.reviewed_at < now() - interval '2 days' THEN rv.reviewed_at + interval '3 hours' END
FROM item_review rv
JOIN checklist_run_item ri ON ri.id = rv.run_item_id
JOIN shift_session s ON s.id = rv.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
WHERE rv.decision = 'REJECTED';

INSERT INTO user_notification (user_id, type, title, body, shift_id, flag_id, created_by, created_at, read_at)
SELECT s.user_id, 'FLAG', 'Подтверждено замечание по смене', f.details, s.id, f.id, f.reviewed_by, f.reviewed_at,
       CASE WHEN f.reviewed_at < now() - interval '2 days' THEN f.reviewed_at + interval '2 hours' END
FROM audit_flag f
JOIN shift_session s ON s.id = f.shift_id
JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
WHERE f.review_status = 'CONFIRMED' AND f.type <> 'REJECTED' AND f.severity IN ('MEDIUM', 'HIGH');

-- 8. Инвентаризации: ежедневная пн–сб, еженедельная по воскресеньям
DO $$
DECLARE
    v_outlet bigint;
    v_list   record;
    c        record;
    v_cnt    bigint;
    v_status text;
    ts0      timestamptz;
BEGIN
    SELECT id INTO v_outlet FROM outlet WHERE name = 'Dostyk Plaza' ORDER BY id LIMIT 1;
    v_status := coalesce((SELECT status FROM inventory_count WHERE submitted_at IS NOT NULL LIMIT 1), 'SUBMITTED');

    FOR c IN
        SELECT s.shift_date AS d, s.user_id, s.id AS shift_id, s.started_at, s.finished_at
        FROM shift_session s JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id
        WHERE s.day_part = 'EVENING' AND s.outlet_id = v_outlet
        ORDER BY s.shift_date
    LOOP
        SELECT * INTO v_list FROM inventory_list
        WHERE active AND position(extract(isodow FROM c.d)::int::text IN replace(weekdays, ',', '')) > 0
        ORDER BY id LIMIT 1;
        CONTINUE WHEN v_list.id IS NULL;

        ts0 := c.finished_at - (60 + random() * 30) * interval '1 minute';
        INSERT INTO inventory_count (list_id, outlet_id, count_date, user_id, shift_id, status, started_at, submitted_at)
        VALUES (v_list.id, v_outlet, c.d, c.user_id, c.shift_id, v_status, ts0,
                ts0 + (14 + random() * 22) * interval '1 minute')
        ON CONFLICT (outlet_id, list_id, count_date) DO NOTHING
        RETURNING id INTO v_cnt;
        CONTINUE WHEN v_cnt IS NULL;
        INSERT INTO demo_ids VALUES ('inv', v_cnt);

        INSERT INTO inventory_line (count_id, product_id, zone, sort_order, cs, slv, ea, none_flag, total,
                                    prev_cs, prev_slv, prev_ea, prev_total, prev_date, confirmed, updated_at)
        SELECT v_cnt, li.product_id, li.zone, li.sort_order,
               n.cs, n.slv, n.ea, n.none_flag,
               CASE WHEN n.none_flag THEN 0 ELSE
                    (n.cs * coalesce(p.case_qty, 0) + n.slv * coalesce(p.sleeve_qty, 0) + n.ea) END,
               pl.cs, pl.slv, pl.ea, pl.total, pl.prev_dt,
               TRUE, ts0 + (n.sort_f * 20) * interval '1 minute'
        FROM inventory_list_item li
        JOIN inventory_product p ON p.id = li.product_id
        CROSS JOIN LATERAL (
            SELECT floor(random() * 3)::int AS cs, floor(random() * 5)::int AS slv, floor(random() * 11)::int AS ea,
                   (random() < 0.05) AS none_flag, li.sort_order::numeric / 400 AS sort_f) n
        LEFT JOIN LATERAL (
            SELECT l2.cs, l2.slv, l2.ea, l2.total, c2.count_date AS prev_dt
            FROM inventory_line l2 JOIN inventory_count c2 ON c2.id = l2.count_id
            WHERE c2.outlet_id = v_outlet AND c2.list_id = v_list.id AND c2.count_date < c.d AND l2.product_id = li.product_id
            ORDER BY c2.count_date DESC LIMIT 1) pl ON TRUE
        WHERE li.list_id = v_list.id;

        -- пара резких скачков для «аномалий»
        UPDATE inventory_line SET total = total * 3, ea = coalesce(ea, 0) * 3
        WHERE count_id = v_cnt AND total > 0 AND random() < 0.03;
    END LOOP;
END $$;

-- 9. Заметки по точке (доска)
DO $$
DECLARE v_outlet bigint; v_dir bigint; v_mgr bigint; n bigint;
BEGIN
    SELECT id INTO v_outlet FROM outlet WHERE name = 'Dostyk Plaza' ORDER BY id LIMIT 1;
    SELECT id INTO v_dir FROM app_user WHERE login = 'ladarina';
    SELECT id INTO v_mgr FROM app_user WHERE login = 'diana';
    IF v_outlet IS NULL OR v_dir IS NULL THEN RETURN; END IF;

    INSERT INTO outlet_note (outlet_id, title, body, status, created_by, created_at, responsible_id, status_by, status_at, closed_at, updated_at)
    VALUES (v_outlet, 'Протекает кран в мойке', 'Капает под раковиной у зоны фритюра', 'DONE', v_dir, now() - interval '9 days', v_mgr, v_mgr, now() - interval '6 days', now() - interval '6 days', now() - interval '6 days')
    RETURNING id INTO n;
    INSERT INTO demo_ids VALUES ('note', n);
    INSERT INTO outlet_note_log (note_id, user_id, status, comment, at) VALUES
        (n, v_mgr, 'IN_PROGRESS', 'Вызвал сантехника', now() - interval '8 days'),
        (n, v_mgr, 'DONE', 'Починили, течи нет', now() - interval '6 days');

    INSERT INTO outlet_note (outlet_id, title, body, status, created_by, created_at, responsible_id, status_by, status_at, updated_at)
    VALUES (v_outlet, 'Заменить амортизирующий лист', 'По ПТО, до конца недели', 'IN_PROGRESS', v_dir, now() - interval '3 days', v_mgr, v_mgr, now() - interval '1 day', now() - interval '1 day')
    RETURNING id INTO n;
    INSERT INTO demo_ids VALUES ('note', n);
    INSERT INTO outlet_note_log (note_id, user_id, status, comment, at) VALUES (n, v_mgr, 'IN_PROGRESS', 'Заказали лист, ждём поставку', now() - interval '1 day');

    INSERT INTO outlet_note (outlet_id, title, body, status, created_by, created_at, updated_at)
    VALUES (v_outlet, 'Проверить температуру шкафа для пирожков', 'Показывает выше нормы по вечерам', 'OPEN', v_dir, now() - interval '1 day', now() - interval '1 day')
    RETURNING id INTO n;
    INSERT INTO demo_ids VALUES ('note', n);

    INSERT INTO outlet_note (outlet_id, title, body, status, created_by, created_at, updated_at)
    VALUES (v_outlet, 'Обновить график на следующую неделю', 'Учесть просьбы о выходных', 'OPEN', v_dir, now() - interval '5 hours', now() - interval '5 hours')
    RETURNING id INTO n;
    INSERT INTO demo_ids VALUES ('note', n);
END $$;

INSERT INTO demo_ids SELECT 'notif', id FROM user_notification
WHERE shift_id IN (SELECT id FROM demo_ids WHERE kind = 'shift');

-- Итог
SELECT (SELECT count(*) FROM demo_ids WHERE kind = 'shift') AS смен,
       (SELECT count(*) FROM checklist_run_item ri JOIN checklist_run r ON r.id = ri.run_id
         JOIN demo_ids d ON d.kind = 'shift' AND d.id = r.shift_id) AS пунктов,
       (SELECT count(*) FROM demo_ids WHERE kind = 'flag') AS флагов,
       (SELECT count(*) FROM demo_ids WHERE kind = 'inv') AS инвентаризаций,
       (SELECT round(avg(score), 1) FROM shift_metrics m JOIN demo_ids d ON d.kind = 'shift' AND d.id = m.shift_id) AS средний_балл;