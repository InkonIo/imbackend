package com.imdemo.im.ext;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imdemo.im.ext.ManagerScheduleParser.Cell;
import com.imdemo.im.ext.ManagerScheduleParser.Person;
import com.imdemo.im.ext.ManagerScheduleParser.Week;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/**
 * Загрузка недельного Excel «Расписание менеджеров» в график ресторана.
 * Смены кладутся в черновик недели (plan_draft / plan_draft_shift) — тот же, что видит «Общий график».
 * Повторная загрузка той же недели заменяет смены тех людей, которые есть в файле; остальных не трогает.
 */
@Service
public class ScheduleImportService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleImportService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SchedPlanService plan;

    public ScheduleImportService(NamedParameterJdbcTemplate jdbc, SchedPlanService plan) {
        this.jdbc = jdbc;
        this.plan = plan;
    }

    private static MapSqlParameterSource p(Object... kv) {
        MapSqlParameterSource m = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) m.addValue((String) kv[i], kv[i + 1]);
        return m;
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }

    private static List<Week> read(String base64) {
        if (base64 == null || base64.isBlank()) throw bad("Файл не передан");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw bad("Файл повреждён при передаче");
        }
        List<Week> weeks;
        try {
            weeks = ManagerScheduleParser.parse(bytes);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        if (weeks.isEmpty()) throw bad("В файле не нашёл листов с расписанием: нужна шапка «Понедельник … Воскресенье» и даты под ней");
        return weeks;
    }

    // ------------------------------------------------------------------ сопоставление имён

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\s-]", " ").trim();
    }

    private static Set<String> tokens(String s) {
        Set<String> t = new TreeSet<>();
        for (String x : norm(s).split("\\s+")) if (!x.isEmpty()) t.add(x);
        return t;
    }

    private record Emp(long id, String name, Set<String> tok) {}

    private List<Emp> employees(long branchId) {
        List<Emp> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
            "SELECT id, full_name FROM ext_employee WHERE branch_id = :b AND NOT is_fired ORDER BY full_name", p("b", branchId))) {
            String n = (String) r.get("full_name");
            out.add(new Emp(((Number) r.get("id")).longValue(), n, tokens(n)));
        }
        return out;
    }

    /** Точное совпадение набора слов; иначе — имя из Excel целиком входит в имя в базе (одно такое). */
    private static Emp match(String excelName, List<Emp> emps) {
        Set<String> t = tokens(excelName);
        if (t.isEmpty()) return null;
        for (Emp e : emps) if (e.tok().equals(t)) return e;
        Emp found = null;
        for (Emp e : emps) {
            if (e.tok().containsAll(t) || (t.size() >= 2 && t.containsAll(e.tok()) && e.tok().size() >= 2)) {
                if (found != null) return null; // неоднозначно
                found = e;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ предпросмотр

    public Map<String, Object> preview(long branchId, String data) {
        List<Week> weeks = read(data);
        List<Emp> emps = employees(branchId);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> ws = new ArrayList<>();
        Set<String> unmatched = new TreeSet<>();
        for (Week w : weeks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sheet", w.sheet());
            m.put("weekStart", w.weekStart());
            m.put("dayNotes", w.dayNotes());
            m.put("warnings", w.warnings());
            List<Map<String, Object>> people = new ArrayList<>();
            int shifts = 0;
            for (Person pr : w.people()) {
                Emp e = match(pr.name(), emps);
                if (e == null) unmatched.add(pr.name());
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", pr.name());
                pm.put("title", pr.title());
                pm.put("employeeId", e == null ? null : e.id());
                pm.put("employeeName", e == null ? null : e.name());
                pm.put("cells", pr.cells());
                people.add(pm);
                for (Cell c : pr.cells()) if ("SHIFT".equals(c.kind())) shifts++;
            }
            m.put("people", people);
            m.put("shifts", shifts);
            m.put("hasDraft", jdbc.queryForObject("SELECT count(*) FROM plan_draft WHERE branch_id = :b AND week_start = :w AND status <> 'ARCHIVED'",
                p("b", branchId, "w", Date.valueOf(LocalDate.parse(w.weekStart()))), Integer.class) > 0);
            ws.add(m);
        }
        out.put("weeks", ws);
        out.put("unmatched", unmatched);
        List<Map<String, Object>> el = new ArrayList<>();
        for (Emp e : emps) el.add(Map.of("id", e.id(), "name", e.name()));
        out.put("employees", el);
        return out;
    }

    // ------------------------------------------------------------------ загрузка

    public record ApplyIn(String data, List<String> weeks, Map<String, Long> map, Boolean publish) {}

    @Transactional
    public Map<String, Object> apply(UserPrincipal u, long branchId, ApplyIn in) {
        List<Week> all = read(in.data());
        Set<String> want = in.weeks() == null ? Set.of() : new HashSet<>(in.weeks());
        if (want.isEmpty()) throw bad("Не выбрано ни одной недели");
        List<Emp> emps = employees(branchId);
        Map<Long, Emp> byId = new HashMap<>();
        for (Emp e : emps) byId.put(e.id(), e);
        Map<String, Long> manual = in.map() == null ? Map.of() : in.map();

        List<Map<String, Object>> results = new ArrayList<>();
        for (Week w : all) {
            if (!want.contains(w.weekStart())) continue;
            LocalDate ws = LocalDate.parse(w.weekStart());

            // кто из файла сопоставлен с сотрудником
            Map<String, Long> ids = new LinkedHashMap<>();
            List<String> skippedPeople = new ArrayList<>();
            for (Person pr : w.people()) {
                Long id = manual.get(pr.name());
                if (id != null && !byId.containsKey(id)) id = null; // чужой ресторан — не берём
                if (id == null) {
                    Emp e = match(pr.name(), emps);
                    if (e != null) id = e.id();
                }
                if (id == null) skippedPeople.add(pr.name()); else ids.put(pr.name(), id);
            }
            if (ids.isEmpty()) {
                results.add(Map.of("weekStart", w.weekStart(), "error", "Никого из файла не нашёл среди сотрудников ресторана"));
                continue;
            }

            Long draftId = null;
            List<Map<String, Object>> ex = jdbc.queryForList(
                "SELECT id FROM plan_draft WHERE branch_id = :b AND week_start = :w AND status = 'DRAFT'",
                p("b", branchId, "w", Date.valueOf(ws)));
            if (ex.isEmpty()) {
                draftId = jdbc.queryForObject(
                    "INSERT INTO plan_draft (branch_id, week_start, created_by) VALUES (:b, :w, :u) RETURNING id",
                    p("b", branchId, "w", Date.valueOf(ws), "u", u.id()), Long.class);
                // если неделя уже опубликована — стартуем с её смен, чтобы остальные люди не пропали при новой публикации
                jdbc.update("""
                    INSERT INTO plan_draft_shift (draft_id, day, position_code, part, start_time, end_time, employee_id, manual, note)
                    SELECT :nd, s.day, s.position_code, s.part, s.start_time, s.end_time, s.employee_id, s.manual, s.note
                    FROM plan_draft_shift s JOIN plan_draft d ON d.id = s.draft_id
                    WHERE d.branch_id = :b AND d.week_start = :w AND d.status = 'PUBLISHED'
                    """, p("nd", draftId, "b", branchId, "w", Date.valueOf(ws)));
            } else {
                draftId = ((Number) ex.get(0).get("id")).longValue();
            }
            long did = draftId == null ? 0 : draftId;

            // заменяем смены только тех, кто есть в файле
            jdbc.update("DELETE FROM plan_draft_shift WHERE draft_id = :d AND employee_id IN (:ids)",
                p("d", did, "ids", new ArrayList<>(new HashSet<>(ids.values()))));

            int added = 0;
            for (Person pr : w.people()) {
                Long eid = ids.get(pr.name());
                if (eid == null) continue;
                for (Cell c : pr.cells()) {
                    if (!"SHIFT".equals(c.kind())) continue;
                    String note = c.note() == null ? null : (c.note().length() > 300 ? c.note().substring(0, 300) : c.note());
                    jdbc.update("""
                        INSERT INTO plan_draft_shift (draft_id, day, position_code, part, start_time, end_time, employee_id, manual, note)
                        VALUES (:d, :day, :pos, :part, CAST(:st AS time), CAST(:en AS time), :e, TRUE, :n)
                        """, p("d", did, "day", Date.valueOf(LocalDate.parse(c.date())), "pos", c.pos(), "part", c.part(),
                            "st", c.start(), "en", c.end(), "e", eid, "n", note));
                    added++;
                }
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("weekStart", w.weekStart());
            r.put("draftId", did);
            r.put("shifts", added);
            r.put("people", ids.size());
            r.put("skippedPeople", skippedPeople);
            if (Boolean.TRUE.equals(in.publish())) {
                plan.publish(u, did, true);
                r.put("published", true);
            }
            results.add(r);
            log.info("schedule import: branch {} week {} draft {} shifts {} by user {}", branchId, ws, did, added, u.id());
        }
        return Map.of("ok", true, "results", results);
    }
}