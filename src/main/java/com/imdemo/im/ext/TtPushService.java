package com.imdemo.im.ext;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.imdemo.im.TimetrackerService;

/**
 * Отправка одного дня из нашей правки (ext_day_override) в Таймтрекер.
 *
 * Как Таймтрекер сохраняет график: PATCH /api/v1/users/{id} с ВСЕЙ карточкой сотрудника, где смены лежат в workDays
 * (ключ — номер дня в году). Поэтому мы каждый раз читаем свежую карточку, меняем в ней ровно один день и отправляем обратно.
 *
 * Защиты: только сотрудники из timetracker.push.allowed-ids (по умолчанию только 64815; "*" — все),
 * только будущие дни, только дни типа work/weekend (факт, отпуск, праздник не трогаем), сначала предпросмотр,
 * после записи перечитываем и сверяем, всё пишется в журнал.
 */
@Service
public class TtPushService {

    /** Ключи верхнего уровня, которые отправляет сам кабинет Таймтрекера (по перехвату запроса). Ещё есть year: его в карточке нет, кабинет добавляет его сам (как и в GET), поэтому подставляем при сборке тела. */
    private static final String KEYS = "id,companyId,departmentId,settingsTemplateId,iin,fullName,isAdmin,email,username,ableVisit,imei,"
        + "phone,phone2,residenceAddress,readStats,ableChooseVisitDay,ableFarVisit,registrationAddress,headFeedback,logo,isRegistered,"
        + "firstname,surname,patronymic,attachLocationPointToSheet,isPush,hikId,maxAccuracy,hiredDate,firedDate,ext_id,isFired,branches,"
        + "workDays,position,settingsFrom,scheduleTemplate,locationPoints,organizations,avatarFile,department";

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    private static final String SQL_CARD = """
        SELECT CASE WHEN jsonb_exists(j, 'data') AND jsonb_typeof(j->'data') = 'object' THEN j->'data' ELSE j END::text
        FROM (SELECT CAST(:j AS jsonb) AS j) x
        """;

    private static final String SQL_MISSING = """
        SELECT coalesce(string_agg(kk, ','), '')
        FROM unnest(string_to_array(:keys, ',')) kk
        WHERE NOT jsonb_exists(CAST(:c AS jsonb), kk)
        """;

    private static final String SQL_ENTRY = """
        SELECT (CAST(:c AS jsonb)->'workDays'->CAST(:k AS text))::text
        """;

    private static final String SQL_BUILD = """
        WITH x AS (SELECT CAST(:c AS jsonb) AS c, CAST(:patch AS jsonb) AS patch, CAST(:k AS text) AS k),
        y AS (SELECT c, k, c->'workDays'->k AS old, (c->'workDays'->k) || patch AS nw FROM x)
        SELECT old::text AS "before", nw::text AS "after",
               ((SELECT coalesce(jsonb_object_agg(kk, c->kk), '{}'::jsonb)
                   FROM unnest(string_to_array(:keys, ',')) kk WHERE jsonb_exists(c, kk))
                || jsonb_build_object('workDays', jsonb_set(c->'workDays', ARRAY[k], nw), 'year', CAST(:year AS text)))::text AS body
        FROM y
        """;

    private static final String SQL_CHANGES = """
        SELECT n.key AS "field", o.value AS "from", n.value AS "to"
        FROM jsonb_each_text(CAST(:after AS jsonb)) n
        LEFT JOIN jsonb_each_text(CAST(:before AS jsonb)) o ON o.key = n.key
        WHERE o.value IS DISTINCT FROM n.value AND n.key NOT IN ('id')
        ORDER BY n.key
        """;

    /** Сколько полей нашего патча в перечитанной карточке не совпало с тем, что отправили. */
    private static final String SQL_VERIFY = """
        SELECT coalesce(string_agg(pe.key, ','), '')
        FROM jsonb_each(CAST(:patch AS jsonb)) pe
        WHERE (CAST(:c AS jsonb)->'workDays'->CAST(:k AS text))->pe.key IS DISTINCT FROM pe.value
        """;

    /** Не изменился ли тип у других дней (факт-поля могут жить своей жизнью, поэтому смотрим только тип). */
    private static final String SQL_OTHERS = """
        SELECT count(*)
        FROM jsonb_each(CAST(:c1 AS jsonb)->'workDays') a
        WHERE a.key <> CAST(:k AS text)
          AND (CAST(:c2 AS jsonb)->'workDays'->a.key->'type') IS DISTINCT FROM (a.value->'type')
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TimetrackerService tt;

    @Value("${timetracker.push.allowed-ids:64815}")
    private String allowedIds;

    public TtPushService(NamedParameterJdbcTemplate jdbc, TimetrackerService tt) {
        this.jdbc = jdbc;
        this.tt = tt;
    }

    private boolean allowed(long emp) {
        String a = allowedIds == null ? "" : allowedIds.trim();
        if (a.equals("*")) return true;
        for (String s : a.split("[,;\\s]+")) if (s.equals(String.valueOf(emp))) return true;
        return false;
    }

    private static Map<String, Object> no(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("reason", reason);
        return m;
    }

    private static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /** Читаемо: «выходной» или «16–0». */
    private String describe(String entryJson) {
        if (entryJson == null) return "нет записи";
        String type = jdbc.queryForObject("SELECT CAST(:e AS jsonb)->>'type'", new MapSqlParameterSource("e", entryJson), String.class);
        if ("weekend".equals(type)) return "выходной";
        String s = jdbc.queryForObject("SELECT CAST(:e AS jsonb)->>'startAt'", new MapSqlParameterSource("e", entryJson), String.class);
        String e = jdbc.queryForObject("SELECT CAST(:e AS jsonb)->>'endAt'", new MapSqlParameterSource("e", entryJson), String.class);
        return (s == null ? "?" : s) + "–" + (e == null ? "?" : e) + (type == null || "work".equals(type) ? "" : " (" + type + ")");
    }

    private static String patchFor(String kind, LocalDate day, LocalTime start, LocalTime end) {
        if ("OFF".equals(kind)) return "{\"type\":\"weekend\",\"mainType\":\"weekend\"}";
        LocalTime e = end.equals(LocalTime.of(23, 59)) ? LocalTime.MIDNIGHT : end;
        boolean overnight = !e.isAfter(start);          // 00:00 и «меньше начала» — следующие сутки
        LocalDate endDay = overnight ? day.plusDays(1) : day;
        return "{\"type\":\"work\",\"mainType\":\"work\""
            + ",\"startAt\":\"" + hhmm(start) + "\",\"endAt\":\"" + hhmm(e) + "\""
            + ",\"startHours\":\"" + String.format("%02d", start.getHour()) + "\",\"startMinutes\":\"" + String.format("%02d", start.getMinute()) + "\""
            + ",\"endHours\":\"" + String.format("%02d", e.getHour()) + "\",\"endMinutes\":\"" + String.format("%02d", e.getMinute()) + "\""
            + ",\"startDate\":\"" + day + " " + hhmm(start) + ":00\",\"endDate\":\"" + endDay + " " + hhmm(e) + ":00\"}";
    }

    private void log(long emp, LocalDate day, String before, String after, String status, Integer http, String msg, Long by) {
        jdbc.update("""
            INSERT INTO ext_tt_push_log(employee_id, day, before_day, after_day, status, http_status, message, pushed_by)
            VALUES (:e, :d, CAST(:b AS jsonb), CAST(:a AS jsonb), :s, :h, :m, :by)
            """, new MapSqlParameterSource().addValue("e", emp).addValue("d", java.sql.Date.valueOf(day))
                .addValue("b", before).addValue("a", after).addValue("s", status).addValue("h", http)
                .addValue("m", msg).addValue("by", by));
    }

    /** confirm=false — только предпросмотр (в Таймтрекер ничего не пишем). */
    public Map<String, Object> push(long emp, String dayStr, boolean confirm, Long userId) {
        LocalDate day;
        try {
            day = LocalDate.parse(dayStr);
        } catch (Exception e) {
            return no("Неверная дата");
        }
        if (!allowed(emp)) return no("Отправка в Таймтрекер пока включена только для: " + allowedIds + ". Это защита на время проб.");
        if (!day.isAfter(LocalDate.now(ALMATY))) return no("В Таймтрекер отправляются только будущие дни (сегодня и прошлые не трогаем).");

        List<Map<String, Object>> ov = jdbc.queryForList("""
            SELECT kind, to_char(start_time, 'HH24:MI') AS s, to_char(end_time, 'HH24:MI') AS e
            FROM ext_day_override WHERE employee_id = :e AND day = :d
            """, new MapSqlParameterSource("e", emp).addValue("d", java.sql.Date.valueOf(day)));
        if (ov.isEmpty()) return no("На сайте для этого дня нет правки, отправлять нечего.");
        String kind = (String) ov.get(0).get("kind");
        LocalTime st = ov.get(0).get("s") == null ? null : LocalTime.parse((String) ov.get(0).get("s"));
        LocalTime en = ov.get(0).get("e") == null ? null : LocalTime.parse((String) ov.get(0).get("e"));

        String path = "/api/v1/users/" + emp
            + "?with%5Bbranches%5D&with%5BworkDays%5D&with%5Bposition%5D&with%5BsettingsFrom%5D&with%5BscheduleTemplate%5D"
            + "&with%5BlocationPoints%5D&with%5Borganizations%5D&with%5BavatarFile%5D&with%5Bdepartment%5D&year=" + day.getYear();
        String card = jdbc.queryForObject(SQL_CARD, new MapSqlParameterSource("j", tt.ttGet(path)), String.class);

        String missing = jdbc.queryForObject(SQL_MISSING, new MapSqlParameterSource("keys", KEYS).addValue("c", card), String.class);
        if (missing != null && !missing.isEmpty()) {
            return no("В карточке из Таймтрекера нет полей, которые отправляет сам кабинет: " + missing + ". Ничего не отправлено.");
        }
        String key = String.valueOf(day.getDayOfYear());
        String entry = jdbc.queryForObject(SQL_ENTRY, new MapSqlParameterSource("c", card).addValue("k", key), String.class);
        if (entry == null) return no("В карточке нет дня №" + key + " за " + day.getYear() + " год.");
        String loc = jdbc.queryForObject("SELECT CAST(:e AS jsonb)->>'localeDate'", new MapSqlParameterSource("e", entry), String.class);
        if (loc == null || !loc.startsWith(day.toString())) {
            return no("День №" + key + " в Таймтрекере — это " + loc + ", а не " + day + ". Ничего не отправлено.");
        }
        String curType = jdbc.queryForObject("SELECT CAST(:e AS jsonb)->>'type'", new MapSqlParameterSource("e", entry), String.class);
        if (!"work".equals(curType) && !"weekend".equals(curType)) {
            return no("В Таймтрекере в этот день стоит «" + curType + "» (факт, отпуск, праздник и т. п.). Такие дни не трогаем.");
        }

        String patch = patchFor(kind, day, st, en);
        Map<String, Object> built = jdbc.queryForMap(SQL_BUILD, new MapSqlParameterSource()
            .addValue("c", card).addValue("patch", patch).addValue("k", key).addValue("keys", KEYS)
            .addValue("year", String.valueOf(day.getYear())));
        String before = (String) built.get("before"), after = (String) built.get("after"), body = (String) built.get("body");

        List<Map<String, Object>> changes = new ArrayList<>(jdbc.queryForList(SQL_CHANGES,
            new MapSqlParameterSource("after", after).addValue("before", before)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("day", day.toString());
        out.put("from", describe(before));
        out.put("to", describe(after));
        out.put("changes", changes);
        out.put("confirmed", confirm);
        if (!confirm) return out;

        TimetrackerService.TtResp r = tt.ttPatch("/api/v1/users/" + emp, body);
        if (r.status() / 100 != 2) {
            String excerpt = r.body() == null ? "" : r.body().substring(0, Math.min(300, r.body().length()));
            log(emp, day, before, after, "ERROR", r.status(), excerpt, userId);
            return no("Таймтрекер ответил " + r.status() + ". День не изменён.");
        }

        // проверка: перечитываем и сверяем
        String card2 = jdbc.queryForObject(SQL_CARD, new MapSqlParameterSource("j", tt.ttGet(path)), String.class);
        String bad = jdbc.queryForObject(SQL_VERIFY, new MapSqlParameterSource("patch", patch).addValue("c", card2).addValue("k", key), String.class);
        Integer others = jdbc.queryForObject(SQL_OTHERS, new MapSqlParameterSource("c1", card).addValue("c2", card2).addValue("k", key), Integer.class);
        boolean same = (bad == null || bad.isEmpty());
        String msg = (same ? "сверено" : "не совпали поля: " + bad) + (others != null && others > 0 ? "; ВНИМАНИЕ: у " + others + " других дней изменился тип" : "");
        log(emp, day, before, after, same ? "OK" : "MISMATCH", r.status(), msg, userId);

        out.put("sent", true);
        out.put("verified", same);
        out.put("message", msg);
        if (others != null && others > 0) out.put("warning", "У других дней изменился тип (" + others + " шт.). Проверьте график в Таймтрекере.");

        // подтянуть месяц из Таймтрекера и убрать нашу правку, раз она теперь там
        if (same) {
            try {
                tt.syncSchedule(day.getYear() + "-" + String.format("%02d", day.getMonthValue()));
                jdbc.update("DELETE FROM ext_day_override WHERE employee_id = :e AND day = :d",
                    new MapSqlParameterSource("e", emp).addValue("d", java.sql.Date.valueOf(day)));
                out.put("synced", true);
            } catch (Exception e) {
                out.put("synced", false);
            }
        }
        return out;
    }
}