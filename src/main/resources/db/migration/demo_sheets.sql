-- =====================================================================
-- ДЕМО: бланки «Чек-лист смены» на 30 дней (для «Архива чек-листов»).
-- Запускать ПОСЛЕ demo_seed.sql. Реальные бланки не трогает: если на дату
-- бланк уже есть, этот день пропускается.
-- Удаляется тем же demo_cleanup.sql (см. там строку про 'sheet').
-- =====================================================================
CREATE OR REPLACE FUNCTION demo_frac(d date, salt text) RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$ SELECT (abs(hashtext(d::text || salt)) % 1000) / 1000.0 $$;

CREATE OR REPLACE FUNCTION demo_nf(n numeric) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT replace(to_char(round(n), 'FM999,999,999,999'), ',', chr(160)) $$;

CREATE OR REPLACE FUNCTION demo_dec(n numeric, dg int) RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT replace(round(n, dg)::text, '.', ',') $$;

-- сколько гостей (GC) было в этот день
CREATE OR REPLACE FUNCTION demo_gc(d date) RETURNS int
LANGUAGE sql IMMUTABLE AS $$
    SELECT round((CASE extract(isodow FROM d)::int
                    WHEN 1 THEN 430 WHEN 2 THEN 440 WHEN 3 THEN 450 WHEN 4 THEN 480
                    WHEN 5 THEN 540 WHEN 6 THEN 600 ELSE 580 END) * (0.90 + 0.20 * demo_frac(d, 'g')))::int $$;

-- средний чек в этот день
CREATE OR REPLACE FUNCTION demo_avg(d date) RETURNS int
LANGUAGE sql IMMUTABLE AS $$
    SELECT round(3300 + 350 * demo_frac(d, 'a') + CASE WHEN extract(isodow FROM d)::int >= 5 THEN 80 ELSE 0 END)::int $$;

-- сэндвичей на 1 гостя
CREATE OR REPLACE FUNCTION demo_sr(d date) RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$ SELECT 0.92 + 0.08 * demo_frac(d, 's') $$;

DROP TABLE IF EXISTS demo_kv;
CREATE TABLE demo_kv (k text, v text);

DO $$
DECLARE
    v_outlet bigint;
    today    date := (now() AT TIME ZONE 'Asia/Almaty')::date;
    d        date;
    sid      bigint;
    gc_prev int; gc_goal int; gc_res int;
    av_prev int; av_goal int; av_res int;
    sd_prev int; sd_goal int; sd_res int;
    k_p int; k_g int; k_r int; m_p int; m_g int; m_r int; dl_p int; dl_g int; dl_r int; ps_p int; ps_g int; cn_p int;
    w numeric[] := ARRAY[.04,.07,.11,.10,.08,.07, .07,.08,.10,.10,.08,.05,.03,.02];
    raw numeric[]; s_raw numeric;
    gcf int[]; gcp int[];
    idx int; part text; i int; n int;
    ach boolean; ach2 boolean; ach3 boolean;
    cum_g numeric; cum_s numeric; h_avg numeric; h_gc int; h_sales numeric;
    morning_u bigint; evening_u bigint; morning_p numeric; evening_p numeric;
    night text;
    prios text[];
    status_ok text[] := ARRAY['идём по плану, средний чек растёт', 'пик отработали без очередей', 'держим скорость обслуживания', 'киоски разгрузили прилавок', 'предлагаем десерт к каждому заказу'];
    status_bad text[] := ARRAY['отстаём по GC, усилили зал', 'длинная очередь на обеде, перераспределили людей', 'простой гриля, вызвали технолога', 'мало гостей из-за погоды'];
    res_ok text[] := ARRAY['Выполнено: предлагали дополнительные позиции, команда включилась', 'Выполнено: цель по среднему чеку достигнута', 'Выполнено за счёт работы на выдаче и киосках'];
    res_bad text[] := ARRAY['Не выполнено: нехватка людей на пике', 'Не выполнено: сбой оборудования в обед', 'Не выполнено: мало гостей, низкий трафик'];
    wastes text[] := ARRAY['Булочки РЕГ, салат, бекон', 'Курица, пирожки, омлет', 'Коул слоу, булочки ББ, яйцо', 'Картофель, панини, блины'];
    nights text[] := ARRAY['Алмат', 'Бакытжан', 'Сания', 'Шерхан'];
BEGIN
    SELECT id INTO v_outlet FROM outlet WHERE name = 'Dostyk Plaza' ORDER BY id LIMIT 1;
    IF v_outlet IS NULL THEN RAISE EXCEPTION 'Не найдена точка Dostyk Plaza'; END IF;

    SELECT coalesce(array_agg(title ORDER BY sort_order, id), ARRAY['Повысить средний чек','Ускорить обслуживание гостей','Улучшить коммуникацию на смене'])
      INTO prios FROM sheet_priority WHERE active AND (outlet_id IS NULL OR outlet_id = v_outlet);

    FOR d IN SELECT DISTINCT s.shift_date FROM shift_session s JOIN demo_ids di ON di.kind = 'shift' AND di.id = s.id ORDER BY 1 LOOP
        INSERT INTO shift_sheet (outlet_id, sheet_date, created_at, updated_at)
        VALUES (v_outlet, d, (d + time '07:50')::timestamp AT TIME ZONE 'Asia/Almaty',
                ((d + 1) + time '00:40')::timestamp AT TIME ZONE 'Asia/Almaty')
        ON CONFLICT (outlet_id, sheet_date) DO NOTHING
        RETURNING id INTO sid;
        CONTINUE WHEN sid IS NULL;
        INSERT INTO demo_ids VALUES ('sheet', sid);
        TRUNCATE demo_kv;

        SELECT s.user_id INTO morning_u FROM shift_session s JOIN demo_ids di ON di.kind='shift' AND di.id=s.id
          WHERE s.shift_date = d AND s.day_part = 'MORNING' LIMIT 1;
        SELECT s.user_id INTO evening_u FROM shift_session s JOIN demo_ids di ON di.kind='shift' AND di.id=s.id
          WHERE s.shift_date = d AND s.day_part = 'EVENING' LIMIT 1;
        SELECT coalesce(m.completion_pct, 90) / 100.0 INTO morning_p FROM shift_metrics m
          JOIN shift_session s ON s.id = m.shift_id WHERE s.shift_date = d AND s.day_part = 'MORNING' AND s.user_id = morning_u LIMIT 1;
        SELECT coalesce(m.completion_pct, 90) / 100.0 INTO evening_p FROM shift_metrics m
          JOIN shift_session s ON s.id = m.shift_id WHERE s.shift_date = d AND s.day_part = 'EVENING' AND s.user_id = evening_u LIMIT 1;
        morning_p := coalesce(morning_p, 0.9);
        evening_p := coalesce(evening_p, 0.9);

        -- цифры дня
        gc_res := demo_gc(d);   gc_prev := demo_gc(d - 7);  gc_goal := round(gc_prev * 1.01);
        av_res := demo_avg(d);  av_prev := demo_avg(d - 7); av_goal := round(av_prev * 1.01);
        sd_res := round(gc_res * demo_sr(d)); sd_prev := round(gc_prev * demo_sr(d - 7)); sd_goal := round(gc_goal * demo_sr(d - 7));
        ach := gc_res >= gc_goal; ach2 := av_res >= av_goal; ach3 := demo_frac(d, 'p3') < 0.6;

        k_g := 28 + floor(demo_frac(d, 'k') * 5)::int;  k_p := k_g - 1;  k_r := k_g + round((demo_frac(d, 'kr') - 0.5) * 4)::int;
        m_g := 10 + floor(demo_frac(d, 'm') * 4)::int;  m_p := m_g - 1;  m_r := m_g + round((demo_frac(d, 'mr') - 0.5) * 3)::int;
        dl_g := 12 + floor(demo_frac(d, 'd') * 5)::int; dl_p := dl_g - 1; dl_r := dl_g + round((demo_frac(d, 'dr') - 0.5) * 4)::int;
        ps_g := 4 + floor(demo_frac(d, 'ps') * 3)::int; ps_p := ps_g;
        cn_p := 100 - k_g - m_g - dl_g - ps_g;

        INSERT INTO demo_kv VALUES
            ('goal.sales.prev', demo_nf(gc_prev::numeric * av_prev)), ('goal.sales.goal', demo_nf(gc_goal::numeric * av_goal)), ('goal.sales.result', demo_nf(gc_res::numeric * av_res)),
            ('goal.gc.prev', demo_nf(gc_prev)), ('goal.gc.goal', demo_nf(gc_goal)), ('goal.gc.result', demo_nf(gc_res)),
            ('goal.avcheck.prev', demo_nf(av_prev)), ('goal.avcheck.goal', demo_nf(av_goal)), ('goal.avcheck.result', demo_nf(av_res)),
            ('goal.voice54.prev', '82%'), ('goal.voice54.goal', '85%'), ('goal.voice54.result', (80 + floor(demo_frac(d, 'v5') * 12))::text || '%'),
            ('goal.voice12.prev', '3%'), ('goal.voice12.goal', '2%'), ('goal.voice12.result', (1 + floor(demo_frac(d, 'v1') * 4))::text || '%'),
            ('goal.gis.prev', '4,8'), ('goal.gis.goal', '4,8'), ('goal.gis.result', '4,8'),
            ('goal.kiosk.prev', k_p || '%'), ('goal.kiosk.goal', k_g || '%'), ('goal.kiosk.result', k_r || '%'),
            ('goal.mobile.prev', m_p || '%'), ('goal.mobile.goal', m_g || '%'), ('goal.mobile.result', m_r || '%'),
            ('goal.postomat.prev', ps_p || '%'), ('goal.postomat.goal', ps_g || '%'), ('goal.postomat.result', (ps_g + round(demo_frac(d, 'psr') * 2 - 1))::int || '%'),
            ('goal.gcpch.prev', demo_dec(8.0 + 1.5 * demo_frac(d - 7, 'gp'), 1)), ('goal.gcpch.goal', demo_dec(8.2 + 1.5 * demo_frac(d - 7, 'gp'), 1)), ('goal.gcpch.result', demo_dec(8.0 + 1.6 * demo_frac(d, 'gp'), 1)),
            ('goal.sandwiches.prev', demo_nf(sd_prev)), ('goal.sandwiches.goal', demo_nf(sd_goal)), ('goal.sandwiches.result', demo_nf(sd_res)),
            ('goal.dlv.prev', dl_p || '%'), ('goal.dlv.goal', dl_g || '%'), ('goal.dlv.result', dl_r || '%'),
            ('goal.dlvwrong.prev', floor(demo_frac(d - 7, 'w') * 4)::text), ('goal.dlvwrong.goal', '0'), ('goal.dlvwrong.result', floor(demo_frac(d, 'w') * 4)::text),
            ('goal.waste.note', wastes[1 + floor(demo_frac(d, 'ws') * 4)::int]),
            ('pct.counter', cn_p::text), ('pct.kiosk', k_g::text), ('pct.delivery', dl_g::text), ('pct.mobile', m_g::text), ('pct.postamat', ps_g::text),
            ('head.night', nights[1 + floor(demo_frac(d, 'n') * 4)::int]),
            ('meta.noauto', '1');

        -- приоритеты и итоги дня
        FOR n IN 1..3 LOOP
            INSERT INTO demo_kv VALUES ('prio.' || n, prios[1 + ((abs(hashtext(d::text)) + n * 3) % array_length(prios, 1))]);
            INSERT INTO demo_kv VALUES ('result.' || n,
                CASE WHEN (CASE n WHEN 1 THEN ach WHEN 2 THEN ach2 ELSE ach3 END)
                     THEN res_ok[1 + ((abs(hashtext(d::text)) + n) % 3)]
                     ELSE res_bad[1 + ((abs(hashtext(d::text)) + n) % 3)] END);
            INSERT INTO demo_kv VALUES ('status.m.' || n, (CASE WHEN ach THEN status_ok ELSE status_bad END)[1 + ((abs(hashtext(d::text)) + n) % 4)]);
            INSERT INTO demo_kv VALUES ('status.e.' || n, (CASE WHEN ach2 THEN status_ok ELSE status_bad END)[1 + ((abs(hashtext(d::text)) + n * 2) % 4)]);
        END LOOP;

        -- почасовка: сначала доли часов, потом факт, нормированный на итог дня
        raw := ARRAY[]::numeric[];
        FOR idx IN 1..14 LOOP raw := raw || (w[idx] * (0.85 + 0.30 * demo_frac(d, 'h' || idx))); END LOOP;
        SELECT sum(x) INTO s_raw FROM unnest(raw) x;
        gcf := ARRAY[]::int[]; gcp := ARRAY[]::int[];
        FOR idx IN 1..14 LOOP
            gcf := gcf || round(gc_res * raw[idx] / s_raw)::int;
            gcp := gcp || round(gc_goal * w[idx])::int;
        END LOOP;
        gcf[4] := gcf[4] + (gc_res - (SELECT sum(x) FROM unnest(gcf) x));   -- остаток — в обеденный пик

        cum_g := 0; cum_s := 0;
        FOR idx IN 1..14 LOOP
            part := CASE WHEN idx <= 6 THEN 'm' ELSE 'e' END;
            i := CASE WHEN idx <= 6 THEN idx - 1 ELSE idx - 7 END;
            h_gc := gcf[idx];
            h_avg := av_res * (0.94 + 0.12 * demo_frac(d, 'ha' || idx));
            h_sales := h_gc * h_avg;
            cum_g := cum_g + h_gc; cum_s := cum_s + h_sales;
            INSERT INTO demo_kv VALUES
                (format('hr.%s.%s.gc.p', part, i), demo_nf(gcp[idx])), (format('hr.%s.%s.gc.f', part, i), demo_nf(h_gc)),
                (format('hr.%s.%s.counter.p', part, i), demo_nf(gcp[idx] * cn_p / 100.0)), (format('hr.%s.%s.counter.f', part, i), demo_nf(h_gc * cn_p / 100.0 * (0.92 + 0.16 * demo_frac(d, 'c' || idx)))),
                (format('hr.%s.%s.kiosk.p', part, i), demo_nf(gcp[idx] * k_g / 100.0)), (format('hr.%s.%s.kiosk.f', part, i), demo_nf(h_gc * k_g / 100.0 * (0.92 + 0.16 * demo_frac(d, 'k' || idx)))),
                (format('hr.%s.%s.mob.p', part, i), demo_nf(gcp[idx] * m_g / 100.0)), (format('hr.%s.%s.mob.f', part, i), demo_nf(h_gc * m_g / 100.0 * (0.90 + 0.20 * demo_frac(d, 'mo' || idx)))),
                (format('hr.%s.%s.sand.p', part, i), demo_nf(sd_goal * w[idx])), (format('hr.%s.%s.sand.f', part, i), demo_nf(h_gc * demo_sr(d))),
                (format('hr.%s.%s.dlv.p', part, i), demo_nf(gcp[idx] * dl_g / 100.0)), (format('hr.%s.%s.dlv.f', part, i), demo_nf(h_gc * dl_g / 100.0 * (0.85 + 0.30 * demo_frac(d, 'dl' || idx)))),
                (format('hr.%s.%s.sales.p', part, i), demo_nf(gcp[idx]::numeric * av_goal)), (format('hr.%s.%s.sales.f', part, i), demo_nf(h_sales)),
                (format('hr.%s.%s.avcheck.v', part, i), demo_nf(cum_s / cum_g)),
                (format('hr.%s.%s.gcpch.v', part, i), demo_dec(7.6 + 1.8 * demo_frac(d, 'gh' || idx), 1)),
                (format('hr.%s.%s.reviews.v', part, i), floor(demo_frac(d, 'rv' || idx) * 3)::text);
        END LOOP;

        -- галочки: утро и вечер по качеству смены
        INSERT INTO demo_kv
        SELECT 'chk.' || x.id || '.' || y.part, '1'
        FROM (
            SELECT 'p' || lpad(g::text, 2, '0') AS id FROM generate_series(1, 25) g
            UNION ALL SELECT 'd' || lpad(g::text, 2, '0') FROM generate_series(1, 21) g
            UNION ALL SELECT 's' || lpad(g::text, 2, '0') FROM generate_series(1, 3) g
            UNION ALL SELECT 'a' || lpad(g::text, 2, '0') FROM generate_series(1, 6) g) x
        CROSS JOIN (VALUES ('m', morning_p), ('e', evening_p)) y (part, p)
        WHERE random() + length(x.id) * 0 < y.p;

        -- сроки хранения: даты, до которых годен продукт
        INSERT INTO demo_kv
        SELECT 'exp.' || g || '.' || y.part, to_char(d + 1 + ((g * 3 + (CASE y.part WHEN 'm' THEN 0 ELSE 1 END)) % 6), 'DD.MM')
        FROM generate_series(0, 20) g CROSS JOIN (VALUES ('m'), ('e')) y (part);

        INSERT INTO shift_sheet_value (sheet_id, field_key, value, updated_by, updated_at)
        SELECT sid, k, v,
               CASE WHEN k LIKE '%.m' OR k LIKE '%.m.%' THEN morning_u WHEN k LIKE '%.e' OR k LIKE '%.e.%' THEN evening_u END,
               ((d + 1) + time '00:35')::timestamp AT TIME ZONE 'Asia/Almaty'
        FROM demo_kv
        ON CONFLICT (sheet_id, field_key) DO NOTHING;
    END LOOP;
END $$;

DROP TABLE IF EXISTS demo_kv;
DROP FUNCTION IF EXISTS demo_frac(date, text), demo_nf(numeric), demo_dec(numeric, int), demo_gc(date), demo_avg(date), demo_sr(date);

SELECT count(*) AS бланков,
       (SELECT count(*) FROM shift_sheet_value v JOIN demo_ids d ON d.kind = 'sheet' AND d.id = v.sheet_id) AS полей
FROM demo_ids WHERE kind = 'sheet';