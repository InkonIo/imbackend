package com.imdemo.im.ext;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/**
 * Правка одного дня в графике сотрудника прямо на сайте.
 * Правка хранится у нас (ext_day_override) и накладывается поверх данных Таймтрекера;
 * в сам Таймтрекер пока ничего не пишется. Право: супер-админ или назначенная ответственная.
 */
@RestController
public class DayEditController {

    public record PushBody(Long employeeId, String day, Boolean confirm) {}
    public record EditBody(Long employeeId, String day, String kind, String start, String end, String note) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final SchedAccess access;
    private final TtPushService push;

    public DayEditController(NamedParameterJdbcTemplate jdbc, SchedAccess access, TtPushService push) {
        this.jdbc = jdbc;
        this.access = access;
        this.push = push;
    }

    private static LocalTime time(String s, String what) {
        try {
            LocalTime t = LocalTime.parse(s == null ? "" : s.trim());
            return t;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неверное время (" + what + "), нужно ЧЧ:ММ");
        }
    }

    private String describe(Long emp, LocalDate day) {
        List<Map<String, Object>> r = jdbc.queryForList("""
            SELECT type, to_char(plan_start, 'HH24:MI') AS s, to_char(plan_end, 'HH24:MI') AS e
            FROM ext_sheet_day_eff WHERE employee_id = :e AND day = :d
            """, new MapSqlParameterSource("e", emp).addValue("d", java.sql.Date.valueOf(day)));
        if (r.isEmpty()) return "нет записи";
        Map<String, Object> m = r.get(0);
        if (m.get("s") == null || "weekend".equals(m.get("type"))) return "выходной";
        return m.get("s") + "–" + m.get("e");
    }

    /** kind: SHIFT (start, end), OFF (выходной) или RESET (вернуть как в Таймтрекере). */
    @PutMapping("/api/sched/day")
    @Transactional
    public Map<String, Object> edit(@AuthenticationPrincipal UserPrincipal p, @RequestBody EditBody b) {
        access.requireManage(p);
        if (b == null || b.employeeId() == null || b.day() == null || b.kind() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Не хватает данных");
        }
        LocalDate day;
        try {
            day = LocalDate.parse(b.day());
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неверная дата");
        }
        Integer ok = jdbc.queryForObject("SELECT count(*) FROM ext_employee WHERE id = :e AND NOT is_fired",
            new MapSqlParameterSource("e", b.employeeId()), Integer.class);
        if (ok == null || ok == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Сотрудник не найден");

        String before = describe(b.employeeId(), day);
        MapSqlParameterSource q = new MapSqlParameterSource()
            .addValue("e", b.employeeId()).addValue("d", java.sql.Date.valueOf(day))
            .addValue("by", p.id())
            .addValue("note", b.note() == null || b.note().isBlank() ? null : b.note().trim());
        switch (b.kind()) {
            case "RESET" -> jdbc.update("DELETE FROM ext_day_override WHERE employee_id = :e AND day = :d", q);
            case "OFF" -> jdbc.update("""
                INSERT INTO ext_day_override(employee_id, day, kind, note, edited_by, edited_at)
                VALUES (:e, :d, 'OFF', :note, :by, now())
                ON CONFLICT (employee_id, day) DO UPDATE SET kind = 'OFF', start_time = NULL, end_time = NULL,
                  note = :note, edited_by = :by, edited_at = now()
                """, q);
            case "SHIFT" -> {
                LocalTime s = time(b.start(), "начало");
                LocalTime e = time(b.end(), "конец");
                // полночь хранится как 23:59, как в Таймтрекере
                if (e.equals(LocalTime.MIDNIGHT)) e = LocalTime.of(23, 59);
                if (s.equals(e)) throw new ApiException(HttpStatus.BAD_REQUEST, "Начало и конец совпадают");
                q.addValue("s", java.sql.Time.valueOf(s)).addValue("en", java.sql.Time.valueOf(e));
                jdbc.update("""
                    INSERT INTO ext_day_override(employee_id, day, kind, start_time, end_time, note, edited_by, edited_at)
                    VALUES (:e, :d, 'SHIFT', :s, :en, :note, :by, now())
                    ON CONFLICT (employee_id, day) DO UPDATE SET kind = 'SHIFT', start_time = :s, end_time = :en,
                      note = :note, edited_by = :by, edited_at = now()
                    """, q);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Неизвестное действие");
        }
        String after = describe(b.employeeId(), day);
        q.addValue("bf", before).addValue("af", after);
        jdbc.update("""
            INSERT INTO ext_day_override_log(employee_id, day, before_text, after_text, edited_by)
            VALUES (:e, :d, :bf, :af, :by)
            """, q);
        return Map.of("ok", true, "before", before, "after", after);
    }

    /** Последние правки этого дня (для окна редактирования). */
    @GetMapping("/api/sched/day/log")
    public List<Map<String, Object>> log(@AuthenticationPrincipal UserPrincipal p,
            @RequestParam long employeeId, @RequestParam String day) {
        access.requireManage(p);
        return jdbc.queryForList("""
            SELECT to_char(l.edited_at AT TIME ZONE 'Asia/Almaty', 'DD.MM HH24:MI') AS "at",
                   coalesce(u.login, '—') AS "by", l.before_text AS "before", l.after_text AS "after"
            FROM ext_day_override_log l LEFT JOIN app_user u ON u.id = l.edited_by
            WHERE l.employee_id = :e AND l.day = :d ORDER BY l.id DESC LIMIT 5
            """, new MapSqlParameterSource("e", employeeId).addValue("d", java.sql.Date.valueOf(LocalDate.parse(day))));
    }

    /** Отправка правки дня в Таймтрекер. confirm=false — предпросмотр «было → станет», confirm=true — запись. */
    @org.springframework.web.bind.annotation.PostMapping("/api/sched/day/push")
    public Map<String, Object> pushDay(@AuthenticationPrincipal UserPrincipal p, @RequestBody PushBody b) {
        access.requireManage(p);
        if (b == null || b.employeeId() == null || b.day() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Не хватает данных");
        return push.push(b.employeeId(), b.day(), Boolean.TRUE.equals(b.confirm()), p.id());
    }
}