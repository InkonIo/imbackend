package com.imdemo.im.ext;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/**
 * Всё, что видит сотрудник. ГЛАВНОЕ ПРАВИЛО: личность берётся ТОЛЬКО из токена (app_user.ext_employee_id).
 * Ни один метод здесь не принимает employeeId от клиента.
 */
@Service
public class EmployeeService {

    static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    /** type из Таймтрекера, которые не считаются рабочей сменой. */
    static final String NOT_SHIFT = "(vac|sick|leave|trip|holiday|celeb|before|fire|dismiss|inweekend)";

    private final NamedParameterJdbcTemplate jdbc;
    private final SchedAccess access;

    public EmployeeService(NamedParameterJdbcTemplate jdbc, SchedAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** id карточки сотрудника для текущего аккаунта. */
    long empId(UserPrincipal p) {
        List<Long> r = jdbc.getJdbcTemplate().query(
            "SELECT ext_employee_id FROM app_user WHERE id = ? AND active AND ext_employee_id IS NOT NULL",
            (rs, i) -> rs.getLong(1), p.id());
        if (r.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "Аккаунт не привязан к сотруднику");
        return r.get(0);
    }

    public Map<String, Object> me(UserPrincipal p) {
        long id = empId(p);
        return jdbc.queryForMap("""
            SELECT e.full_name AS "fullName", pos.title AS position, br.title AS branch
            FROM ext_employee e
            LEFT JOIN ext_position pos ON pos.id = e.position_id
            LEFT JOIN ext_branch br ON br.id = e.branch_id
            WHERE e.id = :id
            """, Map.of("id", id));
    }

    private static YearMonth month(String m) {
        try {
            return m == null || m.isBlank() ? YearMonth.now(ALMATY) : YearMonth.parse(m);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неверный месяц");
        }
    }

    public Map<String, Object> schedule(UserPrincipal p, String monthStr) {
        long id = empId(p);
        YearMonth ym = month(monthStr);
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        MapSqlParameterSource ps = new MapSqlParameterSource().addValue("e", id).addValue("from", from).addValue("to", to);

        List<Map<String, Object>> days = jdbc.queryForList("""
            SELECT extract(day FROM s.day)::int AS day, s.type,
                   CASE WHEN s.type IS DISTINCT FROM 'weekend' THEN to_char(s.plan_start, 'HH24:MI') END AS "planStart",
                   CASE WHEN s.type IS DISTINCT FROM 'weekend' THEN to_char(s.plan_end, 'HH24:MI') END AS "planEnd",
                   to_char(s.fact_in AT TIME ZONE 'Asia/Almaty', 'HH24:MI') AS "factIn",
                   to_char(s.fact_out AT TIME ZONE 'Asia/Almaty', 'HH24:MI') AS "factOut",
                   coalesce(s.worked_min, 0) AS "workedMin",
                   (s.type IS DISTINCT FROM 'weekend' AND s.plan_start IS NOT NULL
                     AND coalesce(s.type, '') !~* '%s') AS planned
            FROM ext_sheet_day_eff s WHERE s.employee_id = :e AND s.day BETWEEN :from AND :to ORDER BY s.day
            """.formatted(NOT_SHIFT), ps);

        Map<String, Object> stats = jdbc.queryForMap("""
            WITH d AS (
              SELECT s.*, (s.type IS DISTINCT FROM 'weekend' AND s.plan_start IS NOT NULL
                           AND coalesce(s.type, '') !~* '%s') AS planned,
                     (s.day <= (now() AT TIME ZONE 'Asia/Almaty')::date) AS past,
                     CASE WHEN s.fact_in IS NOT NULL AND s.plan_start IS NOT NULL
                          THEN extract(epoch FROM ((s.fact_in AT TIME ZONE 'Asia/Almaty') - (s.day + s.plan_start))) / 60 END AS late_min
              FROM ext_sheet_day_eff s WHERE s.employee_id = :e AND s.day BETWEEN :from AND :to
            )
            SELECT count(*) FILTER (WHERE planned)::int AS planned,
                   count(*) FILTER (WHERE planned AND past)::int AS "plannedPast",
                   count(*) FILTER (WHERE fact_in IS NOT NULL)::int AS came,
                   count(*) FILTER (WHERE late_min > 5)::int AS late,
                   count(*) FILTER (WHERE planned AND past AND fact_in IS NULL AND type = 'wasnt')::int AS missed,
                   coalesce(sum(worked_min), 0)::int AS "workedMin",
                   coalesce(sum(CASE WHEN planned THEN extract(epoch FROM (plan_end - plan_start
                            + CASE WHEN plan_end <= plan_start THEN interval '1 day' ELSE interval '0' END)) / 60 END), 0)::int AS "plannedMin"
            FROM d
            """.formatted(NOT_SHIFT), ps);

        List<Map<String, Object>> reqs = jdbc.queryForList("""
            SELECT id, kind, to_char(date_from, 'YYYY-MM-DD') AS "dateFrom", to_char(date_to, 'YYYY-MM-DD') AS "dateTo", to_char(time_from, 'HH24:MI') AS "timeFrom",
                   to_char(time_to, 'HH24:MI') AS "timeTo", status
            FROM emp_request WHERE employee_id = :e AND status IN ('PENDING', 'APPROVED')
              AND date_from <= :to AND date_to >= :from ORDER BY date_from
            """, ps);

        // ближайшая смена (в пределах 30 дней, не обязательно в этом месяце)
        List<Map<String, Object>> next = jdbc.queryForList("""
            SELECT to_char(s.day, 'YYYY-MM-DD') AS date, to_char(s.plan_start, 'HH24:MI') AS "planStart", to_char(s.plan_end, 'HH24:MI') AS "planEnd"
            FROM ext_sheet_day_eff s
            WHERE s.employee_id = :e AND s.day >= (now() AT TIME ZONE 'Asia/Almaty')::date
              AND s.type IS DISTINCT FROM 'weekend' AND s.plan_start IS NOT NULL AND coalesce(s.type, '') !~* '%s'
              AND (s.fact_in IS NULL OR s.day > (now() AT TIME ZONE 'Asia/Almaty')::date)
              AND (s.day > (now() AT TIME ZONE 'Asia/Almaty')::date
                   OR s.plan_end > (now() AT TIME ZONE 'Asia/Almaty')::time OR s.plan_end <= s.plan_start)
            ORDER BY s.day LIMIT 1
            """.formatted(NOT_SHIFT), Map.of("e", id));

        return Map.of("month", ym.toString(), "daysInMonth", ym.lengthOfMonth(), "days", days, "stats", stats,
            "requests", reqs, "next", next.isEmpty() ? Map.of() : next.get(0),
            "leadDays", access.intSetting("request_lead_days", 2));
    }

    /** С кем я в смене в этот день: только имена и время, только мой филиал, без телефонов. */
    public List<Map<String, Object>> coworkers(UserPrincipal p, LocalDate day) {
        long id = empId(p);
        return jdbc.queryForList("""
            SELECT o.full_name AS "fullName", pos.title AS position,
                   to_char(s.plan_start, 'HH24:MI') AS "planStart", to_char(s.plan_end, 'HH24:MI') AS "planEnd"
            FROM ext_employee me
            JOIN ext_employee o ON o.branch_id = me.branch_id AND o.id <> me.id AND NOT o.is_fired
            JOIN ext_sheet_day_eff s ON s.employee_id = o.id AND s.day = :day
            LEFT JOIN ext_position pos ON pos.id = o.position_id
            WHERE me.id = :me AND s.type IS DISTINCT FROM 'weekend' AND s.plan_start IS NOT NULL
              AND coalesce(s.type, '') !~* '%s'
            ORDER BY s.plan_start, o.full_name
            """.formatted(NOT_SHIFT), Map.of("me", id, "day", day));
    }

    // ---------- заявки ----------

    public List<Map<String, Object>> myRequests(UserPrincipal p) {
        long id = empId(p);
        return jdbc.queryForList("""
            SELECT r.id, r.kind, to_char(r.date_from, 'YYYY-MM-DD') AS "dateFrom", to_char(r.date_to, 'YYYY-MM-DD') AS "dateTo",
                   to_char(r.time_from, 'HH24:MI') AS "timeFrom", to_char(r.time_to, 'HH24:MI') AS "timeTo",
                   r.comment, r.status, r.decision_note AS "decisionNote", r.decided_at AS "decidedAt", r.created_at AS "createdAt"
            FROM emp_request r WHERE r.employee_id = :e AND r.date_to >= (now() AT TIME ZONE 'Asia/Almaty')::date - 30
            ORDER BY r.date_from DESC, r.id DESC LIMIT 100
            """, Map.of("e", id));
    }

    public record NewRequest(String kind, LocalDate dateFrom, LocalDate dateTo, LocalTime timeFrom, LocalTime timeTo, String comment) {}

    public Map<String, Object> create(UserPrincipal p, NewRequest r) {
        long id = empId(p);
        if (r == null || r.kind() == null || r.dateFrom() == null) throw bad("Укажите тип и дату");
        String kind = r.kind().trim().toUpperCase();
        if (!kind.equals("DAY_OFF") && !kind.equals("UNAVAILABLE") && !kind.equals("AVAILABLE")) throw bad("Неизвестный тип заявки");
        LocalDate from = r.dateFrom();
        LocalDate to = r.dateTo() == null ? from : r.dateTo();
        LocalDate today = LocalDate.now(ALMATY);
        int lead = access.intSetting("request_lead_days", 2);
        if (to.isBefore(from)) throw bad("Дата «по» раньше даты «с»");
        if (from.isBefore(today.plusDays(lead)))
            throw bad("Заявки принимаются минимум за " + lead + " дн. Ближе — напишите ответственной за расписание.");
        if (from.isAfter(today.plusDays(90))) throw bad("Можно заказывать не дальше чем на 90 дней вперёд");
        if (ChronoUnit.DAYS.between(from, to) >= 14) throw bad("Одна заявка — не больше 14 дней");

        LocalTime tf = null, tt = null;
        if (!kind.equals("DAY_OFF")) {
            tf = r.timeFrom();
            tt = r.timeTo();
            if (tf == null || tt == null || !tt.isAfter(tf)) throw bad("Укажите время «с» и «до» (до позже, чем с)");
        }
        String comment = r.comment() == null ? null : r.comment().trim();
        if (comment != null && comment.length() > 300) throw bad("Комментарий — не длиннее 300 символов");
        if (comment != null && comment.isEmpty()) comment = null;

        Integer pending = jdbc.queryForObject("SELECT count(*) FROM emp_request WHERE employee_id = :e AND status = 'PENDING'",
            Map.of("e", id), Integer.class);
        if (pending != null && pending >= 15) throw bad("Уже 15 заявок ждут ответа. Дождитесь решения или отмените лишние.");

        MapSqlParameterSource ps = new MapSqlParameterSource().addValue("e", id).addValue("kind", kind)
            .addValue("from", from).addValue("to", to).addValue("tf", tf, java.sql.Types.TIME).addValue("tt", tt, java.sql.Types.TIME)
            .addValue("c", comment);
        Integer clash = jdbc.queryForObject("""
            SELECT count(*) FROM emp_request WHERE employee_id = :e AND status IN ('PENDING', 'APPROVED')
              AND date_from <= :to AND date_to >= :from
              AND (:kind = 'DAY_OFF' OR kind = 'DAY_OFF' OR (time_from < CAST(:tt AS time) AND time_to > CAST(:tf AS time)))
            """, ps, Integer.class);
        if (clash != null && clash > 0) throw bad("На эти даты у вас уже есть заявка");

        Long newId = jdbc.queryForObject("""
            INSERT INTO emp_request (employee_id, kind, date_from, date_to, time_from, time_to, comment)
            VALUES (:e, :kind, :from, :to, :tf, :tt, :c) RETURNING id
            """, ps, Long.class);
        return Map.of("id", newId == null ? 0L : newId, "status", "PENDING");
    }

    public void cancel(UserPrincipal p, long requestId) {
        long id = empId(p);
        // чужую заявку тронуть нельзя: условие employee_id = мой
        int n = jdbc.update("UPDATE emp_request SET status = 'CANCELLED' WHERE id = :id AND employee_id = :e AND status = 'PENDING'",
            Map.of("id", requestId, "e", id));
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Заявка не найдена или уже рассмотрена");
    }

    private static ApiException bad(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, m);
    }
}