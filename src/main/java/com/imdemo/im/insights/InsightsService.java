package com.imdemo.im.insights;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Аналитика для директора и супер-админа. Всё считается SQL-ом из уже существующих таблиц,
 * новых таблиц и миграций не нужно. Директор видит только свои точки (user_outlet).
 */
@Service
public class InsightsService {

    static final String TZ = "Asia/Almaty";

    // ---------- общие куски SQL ----------
    static final String P_FROM_TS = "(cast(:from as date)::timestamp at time zone 'Asia/Almaty')";
    static final String P_TO_TS = "((cast(:to as date) + 1)::timestamp at time zone 'Asia/Almaty')";
    static final String P_WM = "m.shift_date between :from and :to and m.outlet_id in (:outlets) and (cast(:uid as bigint) is null or m.user_id = :uid)";
    static final String P_WS = "s.shift_date between :from and :to and s.outlet_id in (:outlets) and (cast(:uid as bigint) is null or s.user_id = :uid)";
    static final String P_WF = "f.created_at >= {FROM_TS} and f.created_at < {TO_TS} and f.outlet_id in (:outlets) and (cast(:uid as bigint) is null or f.user_id = :uid)";
    static final String P_WE = "e.created_at >= {FROM_TS} and e.created_at < {TO_TS} and (e.outlet_id in (:outlets) or (e.outlet_id is null and cast(:uid as bigint) is not null)) and (cast(:uid as bigint) is null or e.user_id = :uid)";
    static final String P_WC = "c.count_date between :from and :to and c.outlet_id in (:outlets) and (cast(:uid as bigint) is null or c.user_id = :uid)";
    static final String P_ANOM = "(l.prev_total is not null and l.prev_total > 0 and l.total is not null and ((l.total >= 3 * l.prev_total and l.total - l.prev_total >= 5) or (l.total = 0 and l.prev_total >= 5)))";

    static final Map<String, String> PRED = new LinkedHashMap<>();
    static {
        PRED.put("{WM}", P_WM); PRED.put("{WS}", P_WS); PRED.put("{WF}", P_WF);
        PRED.put("{WE}", P_WE); PRED.put("{WC}", P_WC); PRED.put("{ANOM}", P_ANOM);
        PRED.put("{FROM_TS}", P_FROM_TS); PRED.put("{TO_TS}", P_TO_TS);
    }
    static final Pattern TS = Pattern.compile("\\{TS\\((.+?)\\)\\}");

    static String sql(String q, boolean uids) {
        q = q.replace("{UIDS}", uids ? " and m.user_id in (:uids) " : "");
        for (int i = 0; i < 2; i++) for (var e : PRED.entrySet()) q = q.replace(e.getKey(), e.getValue());
        Matcher m = TS.matcher(q);
        return m.replaceAll(r -> "to_char(" + r.group(1) + " at time zone 'Asia/Almaty', 'YYYY-MM-DD\"T\"HH24:MI:SS')");
    }

    // ---------- запросы ----------
    static final String Q_TOTALS = """
        select count(*) as shifts,
               count(distinct m.user_id) as managers,
               count(*) filter (where m.finished_at is null) as open_shifts,
               round(avg(m.score), 1) as score_avg,
               round(100.0 * sum(m.items_done) / nullif(sum(m.items_total), 0)) as completion_pct,
               round(100.0 * sum(m.due_on_time) / nullif(sum(m.due_total), 0)) as on_time_pct,
               coalesce(sum(m.items_total), 0) as items_total,
               coalesce(sum(m.items_done), 0) as items_done,
               coalesce(sum(m.items_problem), 0) as items_problem,
               coalesce(sum(m.items_skipped), 0) as items_skipped,
               coalesce(sum(m.items_not_done), 0) as items_not_done,
               coalesce(sum(m.photos_total), 0) as photos,
               coalesce(sum(m.comments_total), 0) as comments,
               coalesce(sum(m.flags_total), 0) as flags_total,
               coalesce(sum(m.flags_confirmed), 0) as flags_confirmed,
               coalesce(sum(m.flags_open), 0) as flags_open,
               coalesce(sum(m.flags_dismissed), 0) as flags_dismissed,
               coalesce(sum(m.items_approved), 0) as items_approved,
               coalesce(sum(m.items_rejected), 0) as items_rejected,
               round(avg(m.duration_min)) as avg_duration_min,
               round(avg(m.active_min)) as avg_active_min
        from shift_metrics m
        where {WM}
        """;

    static final String Q_DAILY = """
        select cast(m.shift_date as varchar) as date,
               count(*) as shifts,
               round(avg(m.score), 1) as score_avg,
               round(100.0 * sum(m.items_done) / nullif(sum(m.items_total), 0)) as completion_pct,
               round(100.0 * sum(m.due_on_time) / nullif(sum(m.due_total), 0)) as on_time_pct,
               sum(m.flags_total) as flags,
               sum(m.flags_confirmed) as flags_confirmed,
               sum(m.items_done) as items_done,
               sum(m.items_skipped + m.items_not_done + m.items_problem) as items_bad
        from shift_metrics m
        where {WM}
        group by m.shift_date
        order by m.shift_date
        """;

    static final String Q_BY_OUTLET = """
        select o.id as outlet_id, o.name as outlet,
               count(*) as shifts,
               count(distinct m.user_id) as managers,
               round(avg(m.score), 1) as score_avg,
               round(100.0 * sum(m.items_done) / nullif(sum(m.items_total), 0)) as completion_pct,
               round(100.0 * sum(m.due_on_time) / nullif(sum(m.due_total), 0)) as on_time_pct,
               sum(m.flags_total) as flags,
               sum(m.flags_confirmed) as flags_confirmed,
               sum(m.items_skipped + m.items_not_done + m.items_problem) as items_bad
        from shift_metrics m join outlet o on o.id = m.outlet_id
        where {WM}
        group by o.id, o.name
        order by score_avg desc nulls last
        """;

    static final String Q_BY_PART_ROLE = """
        select m.day_part, m.shift_role,
               count(*) as shifts,
               round(avg(m.score), 1) as score_avg,
               round(100.0 * sum(m.items_done) / nullif(sum(m.items_total), 0)) as completion_pct,
               round(100.0 * sum(m.due_on_time) / nullif(sum(m.due_total), 0)) as on_time_pct,
               sum(m.flags_total) as flags,
               round(avg(m.duration_min)) as avg_duration_min
        from shift_metrics m
        where {WM}
        group by m.day_part, m.shift_role
        order by m.day_part, m.shift_role
        """;

    static final String Q_LEADERBOARD = """
        select u.id as user_id, u.full_name, u.login, u.active,
               count(*) as shifts,
               count(*) filter (where m.day_part = 'MORNING') as morning,
               count(*) filter (where m.day_part = 'EVENING') as evening,
               count(*) filter (where m.finished_at is null) as open_shifts,
               coalesce(sum(m.score), 0) as score_total,
               round(avg(m.score), 1) as score_avg,
               coalesce(sum(m.items_total), 0) as items_total,
               coalesce(sum(m.items_done), 0) as items_done,
               coalesce(sum(m.items_problem), 0) as items_problem,
               coalesce(sum(m.items_skipped), 0) as items_skipped,
               coalesce(sum(m.items_not_done), 0) as items_not_done,
               round(100.0 * sum(m.items_done) / nullif(sum(m.items_total), 0)) as completion_pct,
               round(100.0 * sum(m.due_on_time) / nullif(sum(m.due_total), 0)) as on_time_pct,
               coalesce(sum(m.photos_total), 0) as photos,
               coalesce(sum(m.comments_total), 0) as comments,
               coalesce(sum(m.flags_total), 0) as flags_total,
               coalesce(sum(m.flags_confirmed), 0) as flags_confirmed,
               coalesce(sum(m.flags_open), 0) as flags_open,
               coalesce(sum(m.flags_dismissed), 0) as flags_dismissed,
               coalesce(sum(m.confirmed_high), 0) as confirmed_high,
               coalesce(sum(m.confirmed_medium), 0) as confirmed_medium,
               coalesce(sum(m.too_fast), 0) as too_fast,
               coalesce(sum(m.late), 0) as late,
               coalesce(sum(m.items_approved), 0) as items_approved,
               coalesce(sum(m.items_rejected), 0) as items_rejected,
               coalesce(sum(m.due_total), 0) as due_total,
               coalesce(sum(m.due_on_time), 0) as due_on_time,
               round(avg(m.duration_min)) as avg_duration_min,
               round(avg(m.active_min)) as avg_active_min,
               (select count(*) from audit_event e
                 where e.user_id = u.id and e.type ilike '%LOGIN%'
                   and e.created_at >= {FROM_TS} and e.created_at < {TO_TS}) as logins,
               (select count(*) from inventory_count c
                 where c.user_id = u.id and c.submitted_at is not null
                   and c.count_date between :from and :to and c.outlet_id in (:outlets)) as inventories,
               (select count(*) from inventory_line l join inventory_count c on c.id = l.count_id
                 where c.user_id = u.id and c.submitted_at is not null
                   and c.count_date between :from and :to and c.outlet_id in (:outlets)
                   and (l.total is not null or l.none_flag)) as inventory_lines
        from shift_metrics m join app_user u on u.id = m.user_id
        where {WM} {UIDS}
        group by u.id, u.full_name, u.login, u.active
        order by score_avg desc nulls last, shifts desc
        """;

    static final String Q_FLAGS = """
        select f.type, f.severity, f.review_status, count(*) as n
        from audit_flag f
        where {WF}
        group by f.type, f.severity, f.review_status
        order by n desc
        """;

    static final String Q_FLAGS_LIST = """
        select f.id, {TS(f.created_at)} as at, f.type, f.severity, f.review_status, f.details,
               f.review_comment, rv.full_name as reviewed_by, {TS(f.reviewed_at)} as reviewed_at,
               u.id as user_id, u.full_name, o.name as outlet, f.shift_id,
               cast(s.shift_date as varchar) as shift_date, s.day_part, ri.title as item_title
        from audit_flag f
        join app_user u on u.id = f.user_id
        join outlet o on o.id = f.outlet_id
        join shift_session s on s.id = f.shift_id
        left join checklist_run_item ri on ri.id = f.run_item_id
        left join app_user rv on rv.id = f.reviewed_by
        where {WF}
        order by f.id desc
        limit :limit
        """;

    static final String Q_PROBLEM_ITEMS = """
        select ri.title,
               count(*) as total,
               count(*) filter (where ri.status = 'DONE') as done,
               count(*) filter (where ri.status = 'PROBLEM') as problem,
               count(*) filter (where ri.status = 'SKIPPED') as skipped,
               count(*) filter (where ri.status = 'PENDING' and s.finished_at is not null) as not_done,
               round(100.0 * count(*) filter (where ri.status = 'DONE') / count(*)) as done_pct
        from shift_session s
        join checklist_run r on r.shift_id = s.id
        join checklist_run_item ri on ri.run_id = r.id
        where {WS}
        group by ri.title
        having count(*) filter (where ri.status in ('PROBLEM', 'SKIPPED')
                                  or (ri.status = 'PENDING' and s.finished_at is not null)) > 0
        order by (count(*) filter (where ri.status in ('PROBLEM', 'SKIPPED')
                                     or (ri.status = 'PENDING' and s.finished_at is not null))) desc, total desc
        limit :limit
        """;

    static final String Q_HEATMAP = """
        select cast(extract(isodow from a.minute at time zone 'Asia/Almaty') as int) as dow,
               cast(extract(hour from a.minute at time zone 'Asia/Almaty') as int) as hour,
               count(*) as minutes
        from activity_minute a join shift_session s on s.id = a.shift_id
        where {WS}
        group by 1, 2
        order by 1, 2
        """;

    static final String Q_INV_COUNTS = """
        select c.id as count_id, cast(c.count_date as varchar) as count_date, c.status, o.name as outlet,
               u.id as user_id, u.full_name,
               {TS(c.started_at)} as started_at, {TS(c.submitted_at)} as submitted_at,
               round(extract(epoch from (c.submitted_at - c.started_at)) / 60.0) as minutes,
               count(l.id) as lines_total,
               count(l.id) filter (where l.total is not null or l.none_flag) as lines_filled,
               count(l.id) filter (where l.none_flag) as lines_none,
               count(l.id) filter (where l.confirmed) as lines_confirmed,
               count(l.id) filter (where {ANOM}) as anomalies,
               coalesce(sum(l.total), 0) as total_units
        from inventory_count c
        join outlet o on o.id = c.outlet_id
        join app_user u on u.id = c.user_id
        left join inventory_line l on l.count_id = c.id
        where {WC}
        group by c.id, o.name, u.id, u.full_name
        order by c.count_date desc, c.id desc
        limit :limit
        """;

    static final String Q_INV_HEAD = """
        select c.id as count_id, cast(c.count_date as varchar) as count_date, c.status, c.outlet_id, o.name as outlet,
               u.id as user_id, u.full_name, c.shift_id,
               {TS(c.started_at)} as started_at, {TS(c.submitted_at)} as submitted_at,
               round(extract(epoch from (c.submitted_at - c.started_at)) / 60.0) as minutes
        from inventory_count c join outlet o on o.id = c.outlet_id join app_user u on u.id = c.user_id
        where c.id = :countId
        """;

    static final String Q_INV_LINES = """
        select l.id as line_id, p.id as product_id, p.name as product, p.unit, p.pack_text, l.zone, l.sort_order,
               l.cs, l.slv, l.ea, l.none_flag, l.total,
               l.prev_cs, l.prev_slv, l.prev_ea, l.prev_total, cast(l.prev_date as varchar) as prev_date,
               case when l.total is not null and l.prev_total is not null then l.total - l.prev_total end as diff,
               case when l.total is not null and l.prev_total > 0 then round((100.0 * (l.total - l.prev_total) / l.prev_total)::numeric) end as diff_pct,
               l.confirmed, {TS(l.updated_at)} as updated_at,
               case when {ANOM} then true else false end as anomaly
        from inventory_line l join inventory_product p on p.id = l.product_id
        where l.count_id = :countId
        order by l.sort_order, l.id
        """;

    static final String Q_INV_PRODUCTS = """
        select p.id as product_id, p.name, p.unit,
               count(l.id) as records,
               round(avg(l.total)::numeric, 1) as avg_total,
               min(l.total) as min_total, max(l.total) as max_total,
               round(stddev_samp(l.total)::numeric, 1) as stddev,
               count(distinct c.user_id) as counters,
               count(l.id) filter (where {ANOM}) as anomalies,
               count(l.id) filter (where l.none_flag) as none_cnt,
               (array_agg(l.total order by c.count_date desc, c.id desc))[1] as last_total,
               cast(max(c.count_date) as varchar) as last_date
        from inventory_line l
        join inventory_count c on c.id = l.count_id
        join inventory_product p on p.id = l.product_id
        where {WC} and c.submitted_at is not null
        group by p.id, p.name, p.unit
        order by anomalies desc, p.name
        """;

    static final String Q_INV_PRODUCT_HISTORY = """
        select cast(c.count_date as varchar) as count_date, c.id as count_id, o.name as outlet,
               u.id as user_id, u.full_name,
               l.cs, l.slv, l.ea, l.none_flag, l.total, l.prev_total,
               case when l.total is not null and l.prev_total is not null then l.total - l.prev_total end as diff,
               l.confirmed, case when {ANOM} then true else false end as anomaly
        from inventory_line l
        join inventory_count c on c.id = l.count_id
        join outlet o on o.id = c.outlet_id
        join app_user u on u.id = c.user_id
        where l.product_id = :productId and {WC}
        order by c.count_date desc, c.id desc
        limit :limit
        """;

    static final String Q_EVENTS = """
        select e.id, {TS(e.created_at)} as at, e.type, e.user_id, e.user_login, e.shift_id, o.name as outlet,
               e.entity_type, e.entity_id, e.details, e.ip, e.device_id, e.user_agent
        from audit_event e left join outlet o on o.id = e.outlet_id
        where {WE} and (cast(:type as varchar) is null or e.type = :type)
        order by e.id desc
        limit :limit offset :offset
        """;

    static final String Q_EVENT_TYPES = """
        select e.type, count(*) as n, {TS(max(e.created_at))} as last_at
        from audit_event e
        where {WE}
        group by e.type
        order by n desc
        """;

    static final String Q_SHIFTS = """
        select s.id as shift_id, cast(s.shift_date as varchar) as shift_date, s.day_part, s.shift_role,
               o.name as outlet, u.id as user_id, u.full_name,
               {TS(s.started_at)} as started_at, {TS(s.finished_at)} as finished_at,
               m.duration_min, m.active_min, m.items_total, m.items_done, m.items_problem, m.items_skipped,
               m.items_not_done, m.completion_pct, m.on_time_pct, m.photos_total, m.flags_total,
               m.flags_confirmed, m.flags_open, m.items_approved, m.items_rejected, m.score,
               (select count(*) from inventory_count c where c.shift_id = s.id) as inventories
        from shift_session s
        join app_user u on u.id = s.user_id
        join outlet o on o.id = s.outlet_id
        left join shift_metrics m on m.shift_id = s.id
        where {WS}
        order by s.started_at desc
        limit :limit offset :offset
        """;

    static final String Q_SHIFT_HEAD = """
        select s.id as shift_id, cast(s.shift_date as varchar) as shift_date, s.day_part, s.shift_role,
               s.outlet_id, o.name as outlet, u.id as user_id, u.full_name, u.login,
               {TS(s.started_at)} as started_at, {TS(s.finished_at)} as finished_at
        from shift_session s join app_user u on u.id = s.user_id join outlet o on o.id = s.outlet_id
        where s.id = :shiftId
        """;

    static final String Q_SHIFT_METRICS = "select * from shift_metrics where shift_id = :shiftId";

    static final String Q_SHIFT_ITEMS = """
        select ri.id as item_id, ri.section_order, ri.section_title, ri.sort_order, ri.title, ri.status,
               ri.director_review, ri.photo_mode, ri.duration_min as planned_min,
               to_char(ri.due_from, 'HH24:MI') as due_from, to_char(ri.due_to, 'HH24:MI') as due_to,
               {TS(ri.started_at)} as started_at, {TS(ri.done_at)} as done_at,
               to_char(ri.done_at at time zone 'Asia/Almaty', 'HH24:MI') as done_time,
               round(extract(epoch from (ri.done_at - ri.started_at)) / 60.0, 1) as actual_min,
               ri.comment, ri.action,
               (select count(*) from checklist_photo p where p.run_item_id = ri.id) as photos,
               (select string_agg(f.type || ':' || f.severity || ':' || f.review_status, ', ' order by f.id)
                  from audit_flag f where f.run_item_id = ri.id) as flags,
               rv.decision as review_decision, rv.comment as review_comment, rv.reviewer_login,
               {TS(rv.reviewed_at)} as reviewed_at
        from checklist_run r
        join checklist_run_item ri on ri.run_id = r.id
        left join item_review rv on rv.run_item_id = ri.id
        where r.shift_id = :shiftId
        order by ri.section_order, ri.sort_order
        """;

    static final String Q_SHIFT_FLAGS = """
        select f.id, {TS(f.created_at)} as at, f.type, f.severity, f.review_status, f.details,
               f.review_comment, rv.full_name as reviewed_by, {TS(f.reviewed_at)} as reviewed_at,
               ri.title as item_title
        from audit_flag f
        left join checklist_run_item ri on ri.id = f.run_item_id
        left join app_user rv on rv.id = f.reviewed_by
        where f.shift_id = :shiftId
        order by f.id
        """;

    static final String Q_SHIFT_EVENTS = """
        select e.id, {TS(e.created_at)} as at, e.type, e.user_login, e.entity_type, e.entity_id,
               e.details, e.ip, e.device_id, e.user_agent
        from audit_event e
        where e.shift_id = :shiftId
        order by e.id
        """;

    static final String Q_SHIFT_ACT_HOURLY = """
        select to_char(a.minute at time zone 'Asia/Almaty', 'HH24') as hour,
               count(*) as minutes, count(distinct a.device_id) as devices
        from activity_minute a
        where a.shift_id = :shiftId
        group by 1
        order by 1
        """;

    static final String Q_SHIFT_ACT_SUM = """
        select count(*) as active_minutes, count(distinct a.device_id) as devices,
               {TS(min(a.minute))} as first_at, {TS(max(a.minute))} as last_at
        from activity_minute a
        where a.shift_id = :shiftId
        """;

    static final String Q_SHIFT_INV = """
        select c.id as count_id, c.status, {TS(c.submitted_at)} as submitted_at,
               count(l.id) as lines_total,
               count(l.id) filter (where l.total is not null or l.none_flag) as lines_filled,
               count(l.id) filter (where {ANOM}) as anomalies
        from inventory_count c left join inventory_line l on l.count_id = c.id
        where c.shift_id = :shiftId
        group by c.id
        """;

    static final String Q_WEEKLY = """
        select cast(date_trunc('week', m.shift_date) as date)::varchar as week, m.user_id,
               count(*) as shifts,
               round(avg(m.score), 1) as score_avg,
               round(100.0 * sum(m.items_done) / nullif(sum(m.items_total), 0)) as completion_pct,
               round(100.0 * sum(m.due_on_time) / nullif(sum(m.due_total), 0)) as on_time_pct,
               sum(m.flags_confirmed) as flags_confirmed
        from shift_metrics m
        where {WM} {UIDS}
        group by 1, m.user_id
        order by 1
        """;

    static final String Q_ITEM_COMPARE = """
        select ri.title, s.user_id,
               count(*) as total,
               count(*) filter (where ri.status = 'DONE') as done,
               count(*) filter (where ri.status in ('SKIPPED', 'PROBLEM')
                                  or (ri.status = 'PENDING' and s.finished_at is not null)) as bad
        from shift_session s
        join checklist_run r on r.shift_id = s.id
        join checklist_run_item ri on ri.run_id = r.id
        where s.shift_date between :from and :to and s.outlet_id in (:outlets) and s.user_id in (:uids)
        group by ri.title, s.user_id
        """;

    static final String Q_ATTENDANCE = """
        select u.id as user_id, u.full_name,
               count(*) filter (where sl.day_part <> 'MIDDLE') as planned,
               count(*) filter (where sl.day_part <> 'MIDDLE' and exists (
                   select 1 from shift_session s
                   where s.user_id = sl.user_id and s.shift_date = sl.slot_date
                     and s.outlet_id = sl.outlet_id and s.day_part = sl.day_part)) as worked,
               count(*) filter (where sl.day_part = 'MIDDLE') as middle_planned,
               count(*) filter (where sl.day_part = 'MIDDLE' and exists (
                   select 1 from shift_session s
                   where s.user_id = sl.user_id and s.shift_date = sl.slot_date and s.outlet_id = sl.outlet_id)) as middle_worked,
               (select count(*) from shift_session s
                 where s.user_id = u.id and s.shift_date between :from and cast(:to as date)
                   and s.outlet_id in (:outlets)
                   and not exists (select 1 from schedule_slot x
                                   where x.user_id = s.user_id and x.slot_date = s.shift_date
                                     and x.outlet_id = s.outlet_id and x.published)) as unplanned
        from schedule_slot sl join app_user u on u.id = sl.user_id
        where sl.published and sl.user_id is not null
          and sl.slot_date between :from and least(cast(:to as date), (now() at time zone 'Asia/Almaty')::date)
          and sl.outlet_id in (:outlets)
          and (cast(:uid as bigint) is null or sl.user_id = :uid)
        group by u.id, u.full_name
        order by u.full_name
        """;

    static final String Q_ABSENCE = """
        select a.user_id, u.full_name, a.kind,
               sum(least(a.date_to, cast(:to as date)) - greatest(a.date_from, cast(:from as date)) + 1) as days
        from staff_absence a join app_user u on u.id = a.user_id
        where a.date_from <= :to and a.date_to >= :from
          and (cast(:uid as bigint) is null or a.user_id = :uid)
          and exists (select 1 from user_outlet uo where uo.user_id = a.user_id and uo.outlet_id in (:outlets))
        group by a.user_id, u.full_name, a.kind
        order by u.full_name, a.kind
        """;

    static final String Q_USER = """
        select u.id, u.login, u.full_name, u.account_role, u.active, {TS(u.created_at)} as created_at,
               (select {TS(max(e.created_at))} from audit_event e where e.user_id = u.id and e.type ilike '%LOGIN%') as last_login,
               (select cast(max(s.shift_date) as varchar) from shift_session s where s.user_id = u.id) as last_shift
        from app_user u where u.id = :uid
        """;

    static final String Q_USER_OUTLETS = """
        select o.id, o.name from user_outlet uo join outlet o on o.id = uo.outlet_id
        where uo.user_id = :uid order by o.name
        """;

    static final String Q_USERS = """
        select distinct u.id, u.full_name, u.login, u.active
        from app_user u
        where u.account_role = 'MANAGER'
          and (exists (select 1 from user_outlet uo where uo.user_id = u.id and uo.outlet_id in (:outlets))
            or exists (select 1 from shift_session s where s.user_id = u.id and s.outlet_id in (:outlets)))
        order by u.full_name
        """;

    static final String Q_OUTLETS = """
        select o.id, o.name from outlet o where o.id in (:outlets) order by o.name
        """;

    // ---------- доступ ----------
    public record Scope(long userId, String role, List<Long> outlets) {}

    private final NamedParameterJdbcTemplate db;

    public InsightsService(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    /** Кто вызывает и какие точки ему видны. Роль берём прямо из app_user, не из названий authorities. */
    public Scope scope(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Object p = auth.getPrincipal();
        Long pid = null;
        String login = null;
        if (p instanceof UserDetails ud) {
            login = ud.getUsername();
        } else if (p instanceof String str) {
            login = str;
        } else if (p != null) {
            // свой тип принципала (например UserPrincipal): достаём id или login через геттеры/accessor-ы record-а
            Object v = call(p, "id", "getId", "userId", "getUserId");
            if (v instanceof Number n) pid = n.longValue();
            Object l = call(p, "login", "getLogin", "username", "getUsername");
            if (l instanceof String str) login = str;
        }
        if (pid == null && login == null) login = auth.getName();

        List<Map<String, Object>> r = List.of();
        var jdbc = db.getJdbcTemplate();
        if (pid != null) r = jdbc.queryForList("select id, account_role from app_user where id = ?", pid);
        if (r.isEmpty() && login != null) r = jdbc.queryForList("select id, account_role from app_user where login = ?", login);
        if (r.isEmpty() && login != null && login.matches("\\d+"))
            r = jdbc.queryForList("select id, account_role from app_user where id = ?", Long.parseLong(login));
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Не удалось определить пользователя из токена");
        long id = ((Number) r.get(0).get("id")).longValue();
        String role = (String) r.get(0).get("account_role");
        List<Long> outlets;
        if ("SUPER_ADMIN".equals(role)) {
            outlets = jdbc.queryForList("select id from outlet", Long.class);
        } else if ("DIRECTOR".equals(role)) {
            outlets = jdbc.queryForList("select outlet_id from user_outlet where user_id = ?", Long.class, id);
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return new Scope(id, role, outlets);
    }

    private static Object call(Object o, String... names) {
        for (String n : names) {
            try {
                Method m = o.getClass().getMethod(n);
                m.setAccessible(true);
                return m.invoke(o);
            } catch (Exception ignored) {
                // пробуем следующее имя
            }
        }
        return null;
    }

    private static List<Long> effective(Scope s, Long outletId) {
        List<Long> base = s.outlets();
        if (outletId != null) return base.contains(outletId) ? List.of(outletId) : List.of(-1L);
        return base.isEmpty() ? List.of(-1L) : base;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of(TZ));
    }

    private MapSqlParameterSource params(Scope s, LocalDate from, LocalDate to, Long outletId, Long uid) {
        LocalDate t = to != null ? to : today();
        LocalDate f = from != null ? from : t.minusDays(29);
        if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
        if (f.isBefore(t.minusDays(366))) f = t.minusDays(366);
        var p = new MapSqlParameterSource();
        p.addValue("from", f);
        p.addValue("to", t);
        p.addValue("outlets", effective(s, outletId));
        p.addValue("uid", uid);
        return p;
    }

    private List<Map<String, Object>> q(String query, MapSqlParameterSource p) {
        return db.queryForList(sql(query, p.hasValue("uids")), p);
    }

    private Map<String, Object> one(String query, MapSqlParameterSource p) {
        List<Map<String, Object>> r = q(query, p);
        return r.isEmpty() ? new LinkedHashMap<>() : r.get(0);
    }

    private static Map<String, Object> range(MapSqlParameterSource p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", String.valueOf(p.getValue("from")));
        m.put("to", String.valueOf(p.getValue("to")));
        return m;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    // ---------- «за что баллы» ----------
    /** Раскладывает итоговый балл по правилам ScoreRules. Остаток (бонус за идеальную смену и т.п.) идёт в "other". */
    static void addScoreParts(Map<String, Object> row) {
        long done = num(row.get("items_done")), approved = num(row.get("items_approved"));
        long skipped = num(row.get("items_skipped")), notDone = num(row.get("items_not_done"));
        long med = num(row.get("confirmed_medium")), high = num(row.get("confirmed_high"));
        long total = num(row.get("score_total"));
        long onTime = num(row.get("due_on_time"));
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("done", done);
        parts.put("on_time", onTime);
        parts.put("approved", 2 * approved);
        parts.put("skipped", -skipped);
        parts.put("not_done", -2 * notDone);
        parts.put("flag_medium", -3 * med);
        parts.put("flag_high", -5 * high);
        long known = parts.values().stream().mapToLong(v -> (Long) v).sum();
        parts.put("other", total - known);
        row.put("score_parts", parts);
    }

    // ---------- публичные методы ----------
    public List<Map<String, Object>> outlets(Scope s) {
        var p = new MapSqlParameterSource("outlets", effective(s, null));
        return q(Q_OUTLETS, p);
    }

    public Map<String, Object> overview(Scope s, LocalDate from, LocalDate to, Long outletId) {
        var p = params(s, from, to, outletId, null);
        p.addValue("limit", 15);
        Map<String, Object> out = range(p);
        out.put("totals", one(Q_TOTALS, p));
        out.put("daily", q(Q_DAILY, p));
        out.put("byOutlet", q(Q_BY_OUTLET, p));
        out.put("byPartRole", q(Q_BY_PART_ROLE, p));
        out.put("flags", q(Q_FLAGS, p));
        out.put("problemItems", q(Q_PROBLEM_ITEMS, p));
        out.put("heatmap", q(Q_HEATMAP, p));
        List<Map<String, Object>> board = leaderboard(p);
        out.put("top", board.stream().limit(3).toList());
        out.put("bottom", board.size() > 3 ? board.subList(Math.max(3, board.size() - 3), board.size()) : List.of());
        var inv = new ArrayList<>(db.queryForList(sql(Q_INV_COUNTS, false), p.addValue("limit", 8)));
        out.put("recentInventories", inv);
        out.put("recentFlags", db.queryForList(sql(Q_FLAGS_LIST, false), p.addValue("limit", 10)));
        return out;
    }

    private List<Map<String, Object>> leaderboard(MapSqlParameterSource p) {
        List<Map<String, Object>> rows = q(Q_LEADERBOARD, p);
        for (Map<String, Object> r : rows) {
            addScoreParts(r);
        }
        return rows;
    }

    public Map<String, Object> managers(Scope s, LocalDate from, LocalDate to, Long outletId) {
        var p = params(s, from, to, outletId, null);
        Map<String, Object> out = range(p);
        List<Map<String, Object>> rows = leaderboard(p);
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("rank", i + 1);
        out.put("managers", rows);
        out.put("team", teamAvg(rows));
        out.put("people", q(Q_USERS, p));
        return out;
    }

    private static Map<String, Object> teamAvg(List<Map<String, Object>> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (String k : List.of("score_avg", "completion_pct", "on_time_pct", "shifts", "flags_total", "flags_confirmed",
                "photos", "avg_duration_min", "avg_active_min")) {
            double sum = 0; int n = 0;
            for (var r : rows) if (r.get(k) instanceof Number x) { sum += x.doubleValue(); n++; }
            t.put(k, n == 0 ? null : Math.round(sum / n * 10) / 10.0);
        }
        t.put("managers", rows.size());
        return t;
    }

    public Map<String, Object> manager(Scope s, long uid, LocalDate from, LocalDate to, Long outletId) {
        var pu = params(s, from, to, outletId, uid);
        Map<String, Object> profile = one(Q_USER, new MapSqlParameterSource("uid", uid));
        if (profile.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> out = range(pu);
        profile.put("outlets", q(Q_USER_OUTLETS, new MapSqlParameterSource("uid", uid)));
        out.put("profile", profile);

        var all = params(s, from, to, outletId, null);
        List<Map<String, Object>> board = leaderboard(all);
        Map<String, Object> me = null;
        int rank = 0;
        for (int i = 0; i < board.size(); i++) {
            if (num(board.get(i).get("user_id")) == uid) { me = board.get(i); rank = i + 1; }
        }
        out.put("kpi", me);
        out.put("rank", rank == 0 ? null : rank);
        out.put("rankOf", board.size());
        out.put("team", teamAvg(board));

        pu.addValue("limit", 20);
        out.put("daily", q(Q_DAILY, pu));
        out.put("byOutlet", q(Q_BY_OUTLET, pu));
        out.put("byPartRole", q(Q_BY_PART_ROLE, pu));
        out.put("flags", q(Q_FLAGS, pu));
        out.put("flagsList", q(Q_FLAGS_LIST, pu));
        out.put("problemItems", q(Q_PROBLEM_ITEMS, pu));
        out.put("heatmap", q(Q_HEATMAP, pu));
        out.put("inventories", q(Q_INV_COUNTS, pu));
        out.put("attendance", q(Q_ATTENDANCE, pu));
        out.put("absences", q(Q_ABSENCE, pu));
        out.put("eventTypes", q(Q_EVENT_TYPES, pu));
        return out;
    }

    public Map<String, Object> shifts(Scope s, Long uid, LocalDate from, LocalDate to, Long outletId, int limit, int offset) {
        var p = params(s, from, to, outletId, uid);
        p.addValue("limit", Math.min(Math.max(limit, 1), 500));
        p.addValue("offset", Math.max(offset, 0));
        Map<String, Object> out = range(p);
        out.put("shifts", q(Q_SHIFTS, p));
        return out;
    }

    public Map<String, Object> shift(Scope s, long shiftId) {
        var p = new MapSqlParameterSource("shiftId", shiftId);
        Map<String, Object> head = one(Q_SHIFT_HEAD, p);
        if (head.isEmpty() || !s.outlets().contains(num(head.get("outlet_id"))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shift", head);
        out.put("metrics", one(Q_SHIFT_METRICS, p));
        out.put("items", q(Q_SHIFT_ITEMS, p));
        out.put("flags", q(Q_SHIFT_FLAGS, p));
        out.put("events", q(Q_SHIFT_EVENTS, p));
        out.put("activity", one(Q_SHIFT_ACT_SUM, p));
        out.put("activityHourly", q(Q_SHIFT_ACT_HOURLY, p));
        out.put("inventories", q(Q_SHIFT_INV, p));
        return out;
    }

    public Map<String, Object> events(Scope s, Long uid, LocalDate from, LocalDate to, Long outletId,
                                      String type, int limit, int offset) {
        var p = params(s, from, to, outletId, uid);
        p.addValue("type", (type == null || type.isBlank()) ? null : type);
        p.addValue("limit", Math.min(Math.max(limit, 1), 500));
        p.addValue("offset", Math.max(offset, 0));
        Map<String, Object> out = range(p);
        out.put("types", q(Q_EVENT_TYPES, p));
        out.put("events", q(Q_EVENTS, p));
        return out;
    }

    public Map<String, Object> inventoryCounts(Scope s, Long uid, LocalDate from, LocalDate to, Long outletId, int limit) {
        var p = params(s, from, to, outletId, uid);
        p.addValue("limit", Math.min(Math.max(limit, 1), 500));
        Map<String, Object> out = range(p);
        out.put("counts", q(Q_INV_COUNTS, p));
        return out;
    }

    public Map<String, Object> inventoryCount(Scope s, long countId) {
        var p = new MapSqlParameterSource("countId", countId);
        Map<String, Object> head = one(Q_INV_HEAD, p);
        if (head.isEmpty() || !s.outlets().contains(num(head.get("outlet_id"))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", head);
        out.put("lines", q(Q_INV_LINES, p));
        return out;
    }

    public Map<String, Object> inventoryProducts(Scope s, Long uid, LocalDate from, LocalDate to, Long outletId) {
        var p = params(s, from, to, outletId, uid);
        Map<String, Object> out = range(p);
        out.put("products", q(Q_INV_PRODUCTS, p));
        return out;
    }

    public Map<String, Object> inventoryProductHistory(Scope s, long productId, Long uid, LocalDate from, LocalDate to,
                                                       Long outletId, int limit) {
        var p = params(s, from, to, outletId, uid);
        p.addValue("productId", productId);
        p.addValue("limit", Math.min(Math.max(limit, 1), 500));
        Map<String, Object> out = range(p);
        out.put("history", q(Q_INV_PRODUCT_HISTORY, p));
        return out;
    }

    public Map<String, Object> compare(Scope s, List<Long> ids, LocalDate from, LocalDate to, Long outletId) {
        if (ids == null || ids.size() < 2 || ids.size() > 6)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужно от 2 до 6 менеджеров");
        var p = params(s, from, to, outletId, null);
        Map<String, Object> out = range(p);
        List<Map<String, Object>> board = leaderboard(p);
        out.put("team", teamAvg(board));
        for (int i = 0; i < board.size(); i++) board.get(i).put("rank", i + 1);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (var r : board) byId.put(num(r.get("user_id")), r);
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Long id : ids) if (byId.containsKey(id)) picked.add(byId.get(id));
        out.put("managers", picked);
        var pu = params(s, from, to, outletId, null);
        pu.addValue("uids", ids);
        out.put("weekly", q(Q_WEEKLY, pu));
        out.put("names", db.getJdbcTemplate().queryForList("select id, full_name from app_user where id in ("
                + String.join(",", ids.stream().map(String::valueOf).toList()) + ")"));

        // По каким пунктам чек-листа люди расходятся сильнее всего
        Map<String, Map<Long, long[]>> pivot = new LinkedHashMap<>();
        for (var r : q(Q_ITEM_COMPARE, pu)) {
            pivot.computeIfAbsent((String) r.get("title"), k -> new LinkedHashMap<>())
                 .put(num(r.get("user_id")), new long[]{num(r.get("total")), num(r.get("done")), num(r.get("bad"))});
        }
        List<Map<String, Object>> spread = new ArrayList<>();
        for (var e : pivot.entrySet()) {
            double min = 101, max = -1; int enough = 0;
            Map<String, Object> perUser = new LinkedHashMap<>();
            for (var u : e.getValue().entrySet()) {
                long[] v = u.getValue();
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("total", v[0]); cell.put("done", v[1]); cell.put("bad", v[2]);
                double badPct = v[0] == 0 ? 0 : 100.0 * v[2] / v[0];
                cell.put("bad_pct", Math.round(badPct));
                perUser.put(String.valueOf(u.getKey()), cell);
                if (v[0] >= 2) { enough++; min = Math.min(min, badPct); max = Math.max(max, badPct); }
            }
            if (enough >= 2 && max > min) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", e.getKey());
                row.put("perUser", perUser);
                row.put("spread", Math.round(max - min));
                spread.add(row);
            }
        }
        spread.sort((a, b) -> Long.compare(num(b.get("spread")), num(a.get("spread"))));
        out.put("itemSpread", spread.stream().limit(25).toList());
        return out;
    }

    public Map<String, Object> attendance(Scope s, Long uid, LocalDate from, LocalDate to, Long outletId) {
        var p = params(s, from, to, outletId, uid);
        Map<String, Object> out = range(p);
        List<Map<String, Object>> rows = q(Q_ATTENDANCE, p);
        for (var r : rows) r.put("missed", num(r.get("planned")) - num(r.get("worked")));
        out.put("people", rows);
        out.put("absences", q(Q_ABSENCE, p));
        return out;
    }

    public Map<String, Object> problemItems(Scope s, Long uid, LocalDate from, LocalDate to, Long outletId, int limit) {
        var p = params(s, from, to, outletId, uid);
        p.addValue("limit", Math.min(Math.max(limit, 1), 200));
        Map<String, Object> out = range(p);
        out.put("items", q(Q_PROBLEM_ITEMS, p));
        return out;
    }
}