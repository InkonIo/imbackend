package com.imdemo.im.ext;

import java.security.SecureRandom;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/** Сторона ответственной за расписание (и супер-админа): заявки, аккаунты сотрудников, выбор ответственной. */
@Service
public class SchedService {

    private static final Logger log = LoggerFactory.getLogger(SchedService.class);
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final NamedParameterJdbcTemplate jdbc;
    private final SchedAccess access;
    private final PasswordEncoder encoder;
    private final SecureRandom rnd = new SecureRandom();

    public SchedService(NamedParameterJdbcTemplate jdbc, SchedAccess access, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.access = access;
        this.encoder = encoder;
    }

    // ---------- caps ----------

    public Map<String, Object> caps(UserPrincipal p) {
        boolean can = access.canManage(p);
        Map<String, Object> m = new HashMap<>();
        m.put("role", p.role().name());
        m.put("canManageSchedule", can);
        m.put("pending", can ? pendingCount() : 0);
        return m;
    }

    public int pendingCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM emp_request WHERE status = 'PENDING' AND date_to >= (now() AT TIME ZONE 'Asia/Almaty')::date",
            Map.of(), Integer.class);
        return n == null ? 0 : n;
    }

    // ---------- заявки ----------

    /** status: PENDING | ACTIVE (ожидают + одобрены) | ALL. month нужен для ACTIVE/ALL. */
    public List<Map<String, Object>> requests(String status, String month, Long branchId) {
        String st = status == null ? "PENDING" : status.toUpperCase();
        MapSqlParameterSource ps = new MapSqlParameterSource().addValue("branch", branchId);
        String where;
        switch (st) {
            case "PENDING" -> where = "r.status = 'PENDING' AND r.date_to >= (now() AT TIME ZONE 'Asia/Almaty')::date";
            case "ACTIVE" -> where = "r.status IN ('PENDING', 'APPROVED')";
            case "ALL" -> where = "TRUE";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Неверный статус");
        }
        if (!st.equals("PENDING")) {
            YearMonth ym;
            try {
                ym = month == null || month.isBlank() ? YearMonth.now(EmployeeService.ALMATY) : YearMonth.parse(month);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Неверный месяц");
            }
            where += " AND r.date_from <= :to AND r.date_to >= :from";
            ps.addValue("from", ym.atDay(1)).addValue("to", ym.atEndOfMonth());
        }
        return jdbc.queryForList("""
            SELECT r.id, r.employee_id AS "employeeId", e.full_name AS "fullName", pos.title AS position, br.title AS branch,
                   r.kind, to_char(r.date_from, 'YYYY-MM-DD') AS "dateFrom", to_char(r.date_to, 'YYYY-MM-DD') AS "dateTo",
                   to_char(r.time_from, 'HH24:MI') AS "timeFrom", to_char(r.time_to, 'HH24:MI') AS "timeTo",
                   r.comment, r.status, r.decision_note AS "decisionNote", r.created_at AS "createdAt",
                   du.full_name AS "decidedBy",
                   (SELECT count(*) FROM ext_sheet_day_eff s WHERE s.employee_id = r.employee_id AND s.day BETWEEN r.date_from AND r.date_to
                      AND s.type IS DISTINCT FROM 'weekend' AND s.plan_start IS NOT NULL AND coalesce(s.type, '') !~* '%1$s')::int AS "myShifts",
                   (SELECT min(c.n) FROM (
                       SELECT g::date AS day,
                              (SELECT count(*) FROM ext_sheet_day_eff s2 JOIN ext_employee o ON o.id = s2.employee_id
                                WHERE s2.day = g::date AND o.branch_id = e.branch_id AND o.id <> e.id AND NOT o.is_fired
                                  AND s2.type IS DISTINCT FROM 'weekend' AND s2.plan_start IS NOT NULL
                                  AND coalesce(s2.type, '') !~* '%1$s') AS n
                       FROM generate_series(r.date_from, r.date_to, interval '1 day') g
                       WHERE EXISTS (SELECT 1 FROM ext_sheet_day_eff m WHERE m.employee_id = r.employee_id AND m.day = g::date
                                       AND m.type IS DISTINCT FROM 'weekend' AND m.plan_start IS NOT NULL
                                       AND coalesce(m.type, '') !~* '%1$s')) c)::int AS "minOthers"
            FROM emp_request r
            JOIN ext_employee e ON e.id = r.employee_id
            LEFT JOIN ext_position pos ON pos.id = e.position_id
            LEFT JOIN ext_branch br ON br.id = e.branch_id
            LEFT JOIN app_user du ON du.id = r.decided_by
            WHERE %2$s AND (CAST(:branch AS bigint) IS NULL OR e.branch_id = CAST(:branch AS bigint))
            ORDER BY (r.status = 'PENDING') DESC, r.date_from, r.id
            LIMIT 500
            """.formatted(EmployeeService.NOT_SHIFT, where), ps);
    }

    public void decide(UserPrincipal p, long id, boolean approve, String note) {
        String n = note == null ? null : note.trim();
        if (n != null && n.length() > 300) throw new ApiException(HttpStatus.BAD_REQUEST, "Комментарий — не длиннее 300 символов");
        if (!approve && (n == null || n.isEmpty()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "При отказе напишите причину — сотрудник её увидит");
        int c = jdbc.update("""
            UPDATE emp_request SET status = :st, decided_by = :by, decided_at = now(), decision_note = :n
            WHERE id = :id AND status = 'PENDING'
            """, new MapSqlParameterSource().addValue("st", approve ? "APPROVED" : "REJECTED").addValue("by", p.id())
            .addValue("n", n == null || n.isEmpty() ? null : n).addValue("id", id));
        if (c == 0) throw new ApiException(HttpStatus.CONFLICT, "Заявка уже рассмотрена или отменена");
        log.info("emp_request {} {} by user {}", id, approve ? "APPROVED" : "REJECTED", p.id());
    }

    // ---------- аккаунты сотрудников ----------

    public List<Map<String, Object>> accounts(String q, Long branchId) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.queryForList("""
            SELECT e.id AS "employeeId", e.full_name AS "fullName", e.phone, pos.title AS position, br.title AS branch,
                   u.id AS "userId", u.login, u.active, u.must_change_password AS "mustChange"
            FROM ext_employee e
            LEFT JOIN ext_position pos ON pos.id = e.position_id
            LEFT JOIN ext_branch br ON br.id = e.branch_id
            LEFT JOIN app_user u ON u.ext_employee_id = e.id
            WHERE NOT e.is_fired
              AND (CAST(:branch AS bigint) IS NULL OR e.branch_id = CAST(:branch AS bigint))
              AND (CAST(:q AS text) IS NULL OR lower(e.full_name) LIKE CAST(:q AS text) OR coalesce(e.phone, '') LIKE CAST(:q AS text))
            ORDER BY (u.id IS NULL) DESC, e.full_name
            LIMIT 1000
            """, new MapSqlParameterSource().addValue("branch", branchId).addValue("q", like));
    }

    /** Логин = телефон цифрами в формате 7XXXXXXXXXX. */
    static String loginFromPhone(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.length() == 11 && d.startsWith("8")) d = "7" + d.substring(1);
        if (d.length() == 10) d = "7" + d;
        return d.length() == 11 && d.startsWith("7") ? d : null;
    }

    private String randomPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Создаёт аккаунты EMPLOYEE. Пароль возвращается ОДИН раз — в базе только хэш. */
    @Transactional
    public List<Map<String, Object>> createAccounts(UserPrincipal by, List<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Никого не выбрали");
        if (employeeIds.size() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "За раз — не больше 200");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long eid : employeeIds) {
            Map<String, Object> row = new HashMap<>();
            row.put("employeeId", eid);
            List<Map<String, Object>> e = jdbc.queryForList("SELECT full_name, phone, is_fired FROM ext_employee WHERE id = :id", Map.of("id", eid));
            if (e.isEmpty()) {
                row.put("status", "skipped");
                row.put("reason", "Сотрудник не найден");
                out.add(row);
                continue;
            }
            String name = String.valueOf(e.get(0).get("full_name"));
            row.put("fullName", name);
            String login = loginFromPhone((String) e.get(0).get("phone"));
            Integer has = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE ext_employee_id = :id", Map.of("id", eid), Integer.class);
            if (Boolean.TRUE.equals(e.get(0).get("is_fired"))) {
                row.put("status", "skipped");
                row.put("reason", "Уволен");
            } else if (has != null && has > 0) {
                row.put("status", "skipped");
                row.put("reason", "Аккаунт уже есть");
            } else if (login == null) {
                row.put("status", "skipped");
                row.put("reason", "Нет корректного телефона (нужен 11 цифр, 7XXXXXXXXXX)");
            } else {
                Integer taken = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE login = :l", Map.of("l", login), Integer.class);
                if (taken != null && taken > 0) {
                    row.put("status", "skipped");
                    row.put("reason", "Логин " + login + " уже занят");
                } else {
                    String pass = randomPassword();
                    jdbc.update("""
                        INSERT INTO app_user (login, password_hash, full_name, account_role, active, must_change_password, ext_employee_id)
                        VALUES (:l, :h, :n, 'EMPLOYEE', TRUE, TRUE, :e)
                        """, new MapSqlParameterSource().addValue("l", login).addValue("h", encoder.encode(pass))
                        .addValue("n", name).addValue("e", eid));
                    row.put("status", "created");
                    row.put("login", login);
                    row.put("password", pass);
                }
            }
            out.add(row);
        }
        log.info("employee accounts created by user {}: {}", by.id(), out.stream().filter(r -> "created".equals(r.get("status"))).count());
        return out;
    }

    @Transactional
    public Map<String, Object> resetPassword(long employeeId) {
        List<Map<String, Object>> u = jdbc.queryForList(
            "SELECT id, login FROM app_user WHERE ext_employee_id = :e AND account_role = 'EMPLOYEE'", Map.of("e", employeeId));
        if (u.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Аккаунт не найден");
        String pass = randomPassword();
        jdbc.update("UPDATE app_user SET password_hash = :h, must_change_password = TRUE WHERE id = :id",
            Map.of("h", encoder.encode(pass), "id", u.get(0).get("id")));
        return Map.of("login", u.get(0).get("login"), "password", pass);
    }

    public void setActive(long employeeId, boolean active) {
        int n = jdbc.update("UPDATE app_user SET active = :a WHERE ext_employee_id = :e AND account_role = 'EMPLOYEE'",
            Map.of("a", active, "e", employeeId));
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Аккаунт не найден");
    }

    // ---------- ответственная за расписание (только супер-админ) ----------

    public Map<String, Object> owner() {
        List<Map<String, Object>> candidates = jdbc.queryForList("""
            SELECT id, full_name AS "fullName", account_role AS role FROM app_user
            WHERE active AND account_role IN ('MANAGER', 'DIRECTOR') ORDER BY full_name
            """, Map.of());
        Map<String, Object> m = new HashMap<>();
        m.put("ownerUserId", access.ownerId());
        m.put("candidates", candidates);
        m.put("leadDays", access.intSetting("request_lead_days", 2));
        return m;
    }

    public void setOwner(UserPrincipal by, Long userId, Integer leadDays) {
        if (userId != null) {
            Integer ok = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE id = :id AND active AND account_role IN ('MANAGER', 'DIRECTOR')", Map.of("id", userId), Integer.class);
            if (ok == null || ok == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Ответственным может быть активный менеджер или директор");
        }
        access.putSetting(SchedAccess.OWNER_KEY, userId == null ? "" : String.valueOf(userId), by.id());
        if (leadDays != null) {
            if (leadDays < 0 || leadDays > 30) throw new ApiException(HttpStatus.BAD_REQUEST, "Срок — от 0 до 30 дней");
            access.putSetting("request_lead_days", String.valueOf(leadDays), by.id());
        }
        log.info("schedule owner set to {} by user {}", userId, by.id());
    }

}
