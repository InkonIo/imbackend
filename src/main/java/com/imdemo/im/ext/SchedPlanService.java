package com.imdemo.im.ext;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imdemo.im.ext.PlanGenerator.Cell;
import com.imdemo.im.ext.PlanGenerator.Emp;
import com.imdemo.im.ext.PlanGenerator.Fixed;
import com.imdemo.im.ext.PlanGenerator.Hist;
import com.imdemo.im.ext.PlanGenerator.Input;
import com.imdemo.im.ext.PlanGenerator.Result;
import com.imdemo.im.ext.PlanGenerator.SlotDef;
import com.imdemo.im.ext.PlanGenerator.Window;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/**
 * Нормы по ресторанам, обучение, сборка черновика недели, ручные правки и публикация.
 * Все даты и время отдаём строками (to_char), чтобы не ловить сдвиг часовых поясов в JSON.
 */
@Service
public class SchedPlanService {

    private static final Logger log = LoggerFactory.getLogger(SchedPlanService.class);

    /** Тот же смысл, что в EmployeeService.NOT_SHIFT: типы дней Таймтрекера, где человека на смене нет. */
    private static final String NOT_SHIFT = "(vac|sick|leave|trip|holiday|celeb|before|fire|dismiss|inweekend)";
    private static final String NOT_SHIFT_OR_WEEKEND = "(vac|sick|leave|trip|holiday|celeb|before|fire|dismiss|inweekend|weekend)";

    /** Должности kln, люди с которыми ходят по своему графику и в автосборку не попадают. Инструктор сюда не входит. */
    private static final String MANAGER_ACCESS = "ARRAY['director','shiftManager','plotManager','scheduleManager','departmentLeader',"
        + "'deputyDirector','serviceLeader','HRSpecialist','technician']::text[]";

    /** Должность в Таймтрекере: менеджеры и директора ходят по своему графику (проверка и по kln, и по названию должности). */
    private static final String MANAGER_TITLE_SQL = "EXISTS (SELECT 1 FROM ext_position pp WHERE pp.id = e.position_id AND pp.title ~ '([Мм]енеджер|[Дд]иректор|[Уу]правляющ)')";

    private static final Pattern HHMM = Pattern.compile("^([01]?\\d|2[0-3]):[0-5]\\d$");
    private static final Set<String> PARTS = Set.of("MORNING", "MID", "EVENING", "NIGHT");

    private final NamedParameterJdbcTemplate jdbc;
    private final SchedAccess access;

    public SchedPlanService(NamedParameterJdbcTemplate jdbc, SchedAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    // ============================================================ вспомогательное

    private static MapSqlParameterSource p(Object... kv) {
        MapSqlParameterSource m = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) m.addValue((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Date d(LocalDate x) {
        return Date.valueOf(x);
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }

    static int toMin(String hhmm) {
        if (hhmm == null || !HHMM.matcher(hhmm.trim()).matches()) throw bad("Время должно быть в виде ЧЧ:ММ, получено: " + hhmm);
        String[] a = hhmm.trim().split(":");
        return Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]);
    }

    /** 23:59 считаем полуночью; если конец не позже начала — смена идёт через полночь. */
    static int normEnd(int start, int end) {
        if (end == 1439) end = 1440;
        if (end <= start) end += 1440;
        return end;
    }

    static String hhmm(int min) {
        min = min % 1440;
        return String.format("%02d:%02d", min / 60, min % 60);
    }

    static String partOf(int start) {
        if (start >= 1260 || start < 240) return "NIGHT";
        if (start < 660) return "MORNING";
        if (start < 900) return "MID";
        return "EVENING";
    }

    private LocalDate defaultStart() {
        LocalDate t = LocalDate.now(ZoneId.of("Asia/Almaty"));
        // пока идёт неделя W, составляется W+2 (понедельник через две недели)
        return t.minusDays(t.getDayOfWeek().getValue() - 1L).plusDays(14);
    }

    private void requireBranch(long branchId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ext_branch WHERE id = :b", p("b", branchId), Integer.class);
        if (n == null || n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Нет такого филиала");
    }

    // ============================================================ справочники

    public List<Map<String, Object>> branches() {
        return jdbc.queryForList("""
            SELECT b.id, b.title,
                   (SELECT count(*) FROM ext_employee e WHERE e.branch_id = b.id AND NOT e.is_fired) AS "employees",
                   (SELECT count(DISTINCT p.employee_id) FROM emp_position p JOIN ext_employee e ON e.id = p.employee_id
                     WHERE e.branch_id = b.id AND NOT e.is_fired) AS "withPositions",
                   (SELECT coalesce(sum(s.need), 0) FROM plan_slot s WHERE s.branch_id = b.id) AS "slotNeed"
            FROM ext_branch b ORDER BY b.title
            """, p());
    }

    public List<Map<String, Object>> positions() {
        return jdbc.queryForList("SELECT code, title, planned, note FROM sched_position ORDER BY sort, code", p());
    }

    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxConsecutive", access.intSetting("plan_max_consecutive", 6));
        m.put("minRestHours", access.intSetting("plan_min_rest_hours", 10));
        return m;
    }

    public void saveSettings(UserPrincipal u, Integer maxConsecutive, Integer minRestHours) {
        if (maxConsecutive != null) {
            if (maxConsecutive < 1 || maxConsecutive > 14) throw bad("Дней подряд: от 1 до 14");
            access.putSetting("plan_max_consecutive", String.valueOf(maxConsecutive), u.id());
        }
        if (minRestHours != null) {
            if (minRestHours < 0 || minRestHours > 24) throw bad("Отдых между сменами: от 0 до 24 часов");
            access.putSetting("plan_min_rest_hours", String.valueOf(minRestHours), u.id());
        }
    }

    // ============================================================ нормы по ресторанам

    public List<Map<String, Object>> slots(long branchId) {
        return jdbc.queryForList("""
            SELECT s.position_code AS "pos", s.part, s.need,
                   to_char(s.start_time, 'HH24:MI') AS "start", to_char(s.end_time, 'HH24:MI') AS "end"
            FROM plan_slot s JOIN sched_position sp ON sp.code = s.position_code
            WHERE s.branch_id = :b ORDER BY sp.sort, s.start_time
            """, p("b", branchId));
    }

    public record SlotIn(String pos, String part, int need, String start, String end) {}

    @Transactional
    public void saveSlots(long branchId, List<SlotIn> list) {
        requireBranch(branchId);
        Set<String> seen = new HashSet<>();
        for (SlotIn s : list) {
            if (s.pos() == null || !PARTS.contains(s.part())) throw bad("Неверная позиция или часть дня");
            if (s.need() < 0 || s.need() > 20) throw bad("Сколько человек: от 0 до 20");
            toMin(s.start());
            toMin(s.end());
            if (!seen.add(s.pos() + "|" + s.part())) throw bad("Позиция " + s.pos() + " указана дважды для части дня " + s.part());
        }
        jdbc.update("DELETE FROM plan_slot WHERE branch_id = :b", p("b", branchId));
        for (SlotIn s : list) {
            jdbc.update("""
                INSERT INTO plan_slot (branch_id, position_code, part, need, start_time, end_time)
                VALUES (:b, :pos, :part, :need, CAST(:st AS time), CAST(:en AS time))
                """, p("b", branchId, "pos", s.pos(), "part", s.part(), "need", s.need(), "st", s.start().trim(), "en", s.end().trim()));
        }
    }

    /** Типовой конец дня: кухня 1 и грузчик с 16:00, прилавок 1, манирум 1. Уже заданные нормы не трогает. */
    @Transactional
    public int preset(long branchId, String name) {
        requireBranch(branchId);
        if (!"evening".equals(name)) throw bad("Неизвестный шаблон: " + name);
        String[][] rows = {{"K", "16:00", "00:00"}, {"DLK", "16:00", "00:30"}, {"C", "16:00", "00:00"}, {"MR", "16:00", "00:00"}};
        int n = 0;
        for (String[] r : rows) {
            n += jdbc.update("""
                INSERT INTO plan_slot (branch_id, position_code, part, need, start_time, end_time)
                VALUES (:b, :pos, 'EVENING', 1, CAST(:st AS time), CAST(:en AS time))
                ON CONFLICT (branch_id, position_code, part) DO NOTHING
                """, p("b", branchId, "pos", r[0], "st", r[1], "en", r[2]));
        }
        return n;
    }

    // ============================================================ сотрудники и позиции

    public List<Map<String, Object>> employees(long branchId) {
        List<Map<String, Object>> list = jdbc.queryForList("""
            SELECT e.id, e.full_name AS "name",
                   (EXISTS (SELECT 1 FROM kln_user k WHERE k.employee_id = e.id AND k.accesses && %s) OR %s) AS "isManager",
                   EXISTS (SELECT 1 FROM kln_user k WHERE k.employee_id = e.id AND 'instructor' = ANY (k.accesses)) AS "isInstructor",
                   EXISTS (SELECT 1 FROM kln_user k WHERE k.employee_id = e.id) AS "inKln",
                   EXISTS (SELECT 1 FROM kln_link l WHERE l.employee_id = e.id) AS "manualLink"
            FROM ext_employee e WHERE e.branch_id = :b AND NOT e.is_fired ORDER BY e.full_name
            """.formatted(MANAGER_ACCESS, MANAGER_TITLE_SQL), p("b", branchId));
        Map<Long, List<Map<String, Object>>> pos = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT p.employee_id AS "eid", p.position_code AS "code", p.source FROM emp_position p
            JOIN ext_employee e ON e.id = p.employee_id WHERE e.branch_id = :b ORDER BY p.position_code
            """, p("b", branchId))) {
            pos.computeIfAbsent(((Number) r.get("eid")).longValue(), k -> new ArrayList<>())
                .add(Map.of("code", r.get("code"), "source", r.get("source")));
        }
        for (Map<String, Object> e : list) e.put("positions", pos.getOrDefault(((Number) e.get("id")).longValue(), List.of()));
        return list;
    }

    @Transactional
    public void addPosition(long employeeId, String code) {
        Integer ok = jdbc.queryForObject("SELECT count(*) FROM sched_position WHERE code = :c", p("c", code), Integer.class);
        if (ok == null || ok == 0) throw bad("Нет такой позиции: " + code);
        try {
            jdbc.update("""
                INSERT INTO emp_position (employee_id, position_code, source) VALUES (:e, :c, 'MANUAL')
                ON CONFLICT (employee_id, position_code) DO NOTHING
                """, p("e", employeeId, "c", code));
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Нет такого сотрудника");
        }
    }

    /** Снять можно только добавленное вручную; позиции из kln меняются в kln. */
    @Transactional
    public int removePosition(long employeeId, String code) {
        return jdbc.update("DELETE FROM emp_position WHERE employee_id = :e AND position_code = :c AND source = 'MANUAL'",
            p("e", employeeId, "c", code));
    }

    // ============================================================ обучение (TR / TRN)

    public List<Map<String, Object>> trainings(long branchId) {
        return jdbc.queryForList("""
            SELECT t.id, t.instructor_id AS "instructorId", i.full_name AS "instructor",
                   t.trainee_id AS "traineeId", coalesce(s.full_name, t.trainee_name) AS "trainee",
                   to_char(t.date_from, 'YYYY-MM-DD') AS "from", to_char(t.date_to, 'YYYY-MM-DD') AS "to",
                   to_char(t.start_time, 'HH24:MI') AS "start", to_char(t.end_time, 'HH24:MI') AS "end", t.comment
            FROM plan_training t JOIN ext_employee i ON i.id = t.instructor_id LEFT JOIN ext_employee s ON s.id = t.trainee_id
            WHERE t.branch_id = :b AND t.date_to >= (now() AT TIME ZONE 'Asia/Almaty')::date - 30
            ORDER BY t.date_from DESC
            """, p("b", branchId));
    }

    public record TrainingIn(long instructorId, Long traineeId, String traineeName, String from, String to, String start, String end, String comment) {}

    public long addTraining(UserPrincipal u, long branchId, TrainingIn t) {
        requireBranch(branchId);
        LocalDate from, to;
        try {
            from = LocalDate.parse(t.from());
            to = LocalDate.parse(t.to());
        } catch (Exception e) {
            throw bad("Даты должны быть в виде ГГГГ-ММ-ДД");
        }
        if (to.isBefore(from)) throw bad("Дата «по» раньше даты «с»");
        String st = t.start() == null || t.start().isBlank() ? "08:00" : t.start();
        String en = t.end() == null || t.end().isBlank() ? "16:00" : t.end();
        toMin(st);
        toMin(en);
        if (t.traineeId() != null && t.traineeId() == t.instructorId()) throw bad("Инструктор и стажёр — один человек");
        String tn = t.traineeName() == null ? null : t.traineeName().trim();
        if (tn != null && tn.length() > 120) throw bad("Имя стажёра длиннее 120 символов");
        if (t.traineeId() != null) tn = null;
        String c = t.comment() == null ? null : t.comment().trim();
        if (c != null && c.length() > 300) throw bad("Комментарий длиннее 300 символов");
        Long id = jdbc.queryForObject("""
            INSERT INTO plan_training (branch_id, instructor_id, trainee_id, trainee_name, date_from, date_to, start_time, end_time, comment, created_by)
            VALUES (:b, :i, :s, :tn, :f, :t, CAST(:st AS time), CAST(:en AS time), :c, :u) RETURNING id
            """, p("b", branchId, "i", t.instructorId(), "s", t.traineeId(), "tn", tn == null || tn.isEmpty() ? null : tn, "f", d(from), "t", d(to), "st", st, "en", en,
                "c", c == null || c.isEmpty() ? null : c, "u", u.id()), Long.class);
        return id == null ? 0 : id;
    }

    public int deleteTraining(long branchId, long id) {
        return jdbc.update("DELETE FROM plan_training WHERE id = :id AND branch_id = :b", p("id", id, "b", branchId));
    }

    // ============================================================ загрузка данных для генератора

    private record Loaded(Input input, Map<Long, String> names, int pendingRequests,
                          List<Map<String, Object>> blockedView) {}

    private Loaded load(long branchId, LocalDate from, int days, List<Fixed> extraFixed) {
        LocalDate to = from.plusDays(days - 1L);

        Map<Long, String> names = new HashMap<>();
        Map<Long, Set<String>> posOf = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT p.employee_id AS "eid", p.position_code AS "code" FROM emp_position p
            JOIN ext_employee e ON e.id = p.employee_id WHERE e.branch_id = :b
            """, p("b", branchId))) {
            posOf.computeIfAbsent(((Number) r.get("eid")).longValue(), k -> new HashSet<>()).add((String) r.get("code"));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, full_name FROM ext_employee WHERE branch_id = :b", p("b", branchId))) {
            names.put(((Number) r.get("id")).longValue(), (String) r.get("full_name"));
        }
        // в график попадают все работающие, кроме менеджеров (у них свой график); без позиций ни на какую смену не встанут
        List<Emp> emps = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT e.id FROM ext_employee e
            WHERE e.branch_id = :b AND NOT e.is_fired
              AND NOT EXISTS (SELECT 1 FROM kln_user k WHERE k.employee_id = e.id AND k.accesses && %s)
              AND NOT %s
            """.formatted(MANAGER_ACCESS, MANAGER_TITLE_SQL), p("b", branchId))) {
            long id = ((Number) r.get("id")).longValue();
            emps.add(new Emp(id, names.getOrDefault(id, "#" + id), posOf.getOrDefault(id, new HashSet<>())));
        }

        // отпуск, больничный и т. п. по данным Таймтрекера
        Set<String> blocked = new HashSet<>();
        List<Map<String, Object>> view = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT d.employee_id AS "eid", to_char(d.day, 'YYYY-MM-DD') AS "day", d.type
            FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id
            WHERE e.branch_id = :b AND d.day BETWEEN :f AND :t AND d.type ~* :ns
            """, p("b", branchId, "f", d(from), "t", d(to), "ns", NOT_SHIFT))) {
            long eid = ((Number) r.get("eid")).longValue();
            blocked.add(PlanGenerator.blockKey(eid, LocalDate.parse((String) r.get("day"))));
            view.add(Map.of("employeeId", eid, "day", r.get("day"), "kind", "ABSENCE", "note", String.valueOf(r.get("type"))));
        }

        // одобренные заявки (на день раньше и позже — для смен через полночь)
        List<Window> windows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT r.employee_id AS "eid", to_char(g::date, 'YYYY-MM-DD') AS "day", r.kind,
                   to_char(r.time_from, 'HH24:MI') AS "tf", to_char(r.time_to, 'HH24:MI') AS "tt"
            FROM emp_request r JOIN ext_employee e ON e.id = r.employee_id,
                 generate_series(r.date_from::timestamp, r.date_to::timestamp, interval '1 day') g
            WHERE r.status = 'APPROVED' AND e.branch_id = :b AND r.date_to >= :f AND r.date_from <= :t
            """, p("b", branchId, "f", d(from.minusDays(1)), "t", d(to.plusDays(1))))) {
            long eid = ((Number) r.get("eid")).longValue();
            LocalDate day = LocalDate.parse((String) r.get("day"));
            String kind = (String) r.get("kind");
            boolean inWeek = !day.isBefore(from) && !day.isAfter(to);
            if ("DAY_OFF".equals(kind)) {
                blocked.add(PlanGenerator.blockKey(eid, day));
                if (inWeek) view.add(Map.of("employeeId", eid, "day", r.get("day"), "kind", "DAY_OFF", "note", "выходной"));
            } else {
                int a = toMin((String) r.get("tf"));
                int b = toMin((String) r.get("tt"));
                boolean avail = "AVAILABLE".equals(kind);
                windows.add(new Window(eid, day, a, b, avail));
                if (inWeek) {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("employeeId", eid);
                    v.put("day", r.get("day"));
                    v.put("kind", avail ? "AVAILABLE" : "UNAVAILABLE");
                    v.put("from", r.get("tf"));
                    v.put("to", r.get("tt"));
                    view.add(v);
                }
            }
        }

        // что стояло в Таймтрекере до начала недели (отдых и серия подряд)
        List<Hist> hist = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT d.employee_id AS "eid", to_char(d.day, 'YYYY-MM-DD') AS "day",
                   (extract(hour FROM d.plan_start) * 60 + extract(minute FROM d.plan_start))::int AS "s",
                   (extract(hour FROM d.plan_end) * 60 + extract(minute FROM d.plan_end))::int AS "e"
            FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id
            WHERE e.branch_id = :b AND d.day BETWEEN :f AND :t AND d.plan_start IS NOT NULL AND d.plan_end IS NOT NULL
              AND coalesce(d.type, '') !~* :ns
            """, p("b", branchId, "f", d(from.minusDays(7)), "t", d(from.minusDays(1)), "ns", NOT_SHIFT_OR_WEEKEND))) {
            int s = ((Number) r.get("s")).intValue();
            int e = normEnd(s, ((Number) r.get("e")).intValue());
            hist.add(new Hist(((Number) r.get("eid")).longValue(), LocalDate.parse((String) r.get("day")), s, e));
        }
        // опубликованные у нас недели (в Таймтрекере их может ещё не быть: график составляют на две недели вперёд);
        // добавляются после данных Таймтрекера и для того же дня их заменяют
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT s.employee_id AS "eid", to_char(s.day, 'YYYY-MM-DD') AS "day",
                   s.start_min AS "s", ((s.start_min + s.dur_min) % 1440) AS "e"
            FROM plan_shift_eff s
            WHERE s.branch_id = :b AND s.status = 'PUBLISHED' AND s.day BETWEEN :f AND :t
              AND s.position_code NOT IN ('TR', 'TRN')
            ORDER BY s.day, s.start_min
            """, p("b", branchId, "f", d(from.minusDays(7)), "t", d(from.minusDays(1))))) {
            int s = ((Number) r.get("s")).intValue();
            int e = normEnd(s, ((Number) r.get("e")).intValue());
            hist.add(new Hist(((Number) r.get("eid")).longValue(), LocalDate.parse((String) r.get("day")), s, e));
        }

        List<SlotDef> slots = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT s.position_code AS "pos", s.part, s.need, sp.sort,
                   (extract(hour FROM s.start_time) * 60 + extract(minute FROM s.start_time))::int AS "st",
                   (extract(hour FROM s.end_time) * 60 + extract(minute FROM s.end_time))::int AS "en"
            FROM plan_slot s JOIN sched_position sp ON sp.code = s.position_code WHERE s.branch_id = :b AND s.need > 0
            """, p("b", branchId))) {
            int st = ((Number) r.get("st")).intValue();
            slots.add(new SlotDef((String) r.get("pos"), (String) r.get("part"), ((Number) r.get("need")).intValue(),
                st, normEnd(st, ((Number) r.get("en")).intValue()), ((Number) r.get("sort")).intValue()));
        }

        // обучение превращаем в заранее поставленные смены TR / TRN
        List<Fixed> fixed = new ArrayList<>(extraFixed);
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT t.instructor_id AS "i", t.trainee_id AS "s", to_char(g::date, 'YYYY-MM-DD') AS "day", t.comment,
                   (extract(hour FROM t.start_time) * 60 + extract(minute FROM t.start_time))::int AS "st",
                   (extract(hour FROM t.end_time) * 60 + extract(minute FROM t.end_time))::int AS "en"
            FROM plan_training t, generate_series(t.date_from::timestamp, t.date_to::timestamp, interval '1 day') g
            WHERE t.branch_id = :b AND t.date_to >= :f AND t.date_from <= :t AND g::date BETWEEN :f AND :t
            """, p("b", branchId, "f", d(from), "t", d(to)))) {
            LocalDate day = LocalDate.parse((String) r.get("day"));
            int st = ((Number) r.get("st")).intValue();
            int en = normEnd(st, ((Number) r.get("en")).intValue());
            String note = (String) r.get("comment");
            String part = partOf(st);
            fixed.add(new Fixed(day, "TR", part, st, en, ((Number) r.get("i")).longValue(), note));
            if (r.get("s") != null) fixed.add(new Fixed(day, "TRN", part, st, en, ((Number) r.get("s")).longValue(), note));
        }

        Integer pending = jdbc.queryForObject("""
            SELECT count(*) FROM emp_request r JOIN ext_employee e ON e.id = r.employee_id
            WHERE r.status = 'PENDING' AND e.branch_id = :b AND r.date_to >= :f AND r.date_from <= :t
            """, p("b", branchId, "f", d(from), "t", d(to)), Integer.class);

        Input in = new Input(from, days, emps, slots, fixed, blocked, windows, hist,
            access.intSetting("plan_max_consecutive", 6), access.intSetting("plan_min_rest_hours", 10) * 60);
        return new Loaded(in, names, pending == null ? 0 : pending, view);
    }

    // ============================================================ сборка черновика

    @Transactional
    public Map<String, Object> generate(UserPrincipal u, long branchId, LocalDate weekStart) {
        requireBranch(branchId);
        LocalDate from = weekStart == null ? defaultStart() : weekStart;
        if (from.isBefore(LocalDate.now(ZoneId.of("Asia/Almaty")))) throw bad("Нельзя собирать график на прошедшие дни");

        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT id FROM plan_draft WHERE branch_id = :b AND week_start = :w AND status = 'DRAFT'", p("b", branchId, "w", d(from)));
        long draftId;
        List<Fixed> manual = new ArrayList<>();
        Set<String> manualKeys = new HashSet<>();
        if (existing.isEmpty()) {
            Long id = jdbc.queryForObject("INSERT INTO plan_draft (branch_id, week_start, created_by) VALUES (:b, :w, :u) RETURNING id",
                p("b", branchId, "w", d(from), "u", u.id()), Long.class);
            draftId = id == null ? 0 : id;
        } else {
            draftId = ((Number) existing.get(0).get("id")).longValue();
            // ручные правки с человеком остаются на месте, всё остальное пересобирается
            for (Map<String, Object> r : jdbc.queryForList("""
                SELECT to_char(day, 'YYYY-MM-DD') AS "day", position_code AS "pos", part, employee_id AS "eid", note,
                       (extract(hour FROM start_time) * 60 + extract(minute FROM start_time))::int AS "st",
                       (extract(hour FROM end_time) * 60 + extract(minute FROM end_time))::int AS "en"
                FROM plan_draft_shift WHERE draft_id = :d AND manual AND employee_id IS NOT NULL
                """, p("d", draftId))) {
                int st = ((Number) r.get("st")).intValue();
                int en = normEnd(st, ((Number) r.get("en")).intValue());
                LocalDate day = LocalDate.parse((String) r.get("day"));
                long eid = ((Number) r.get("eid")).longValue();
                manual.add(new Fixed(day, (String) r.get("pos"), (String) r.get("part"), st, en, eid, (String) r.get("note")));
                manualKeys.add(day + "|" + r.get("pos") + "|" + eid + "|" + st);
            }
            jdbc.update("DELETE FROM plan_draft_shift WHERE draft_id = :d AND NOT (manual AND employee_id IS NOT NULL)", p("d", draftId));
        }

        Loaded ld = load(branchId, from, 7, manual);
        Result res = PlanGenerator.generate(ld.input());

        int filled = 0, empty = 0;
        List<Map<String, Object>> emptyList = new ArrayList<>();
        for (Cell c : res.cells()) {
            if (c.empId() != null && manualKeys.contains(c.day() + "|" + c.pos() + "|" + c.empId() + "|" + c.start())) {
                filled++;
                continue;
            }
            jdbc.update("""
                INSERT INTO plan_draft_shift (draft_id, day, position_code, part, start_time, end_time, employee_id, manual, note)
                VALUES (:d, :day, :pos, :part, CAST(:st AS time), CAST(:en AS time), :e, FALSE, :n)
                """, p("d", draftId, "day", d(c.day()), "pos", c.pos(), "part", c.part(), "st", hhmm(c.start()), "en", hhmm(c.end()),
                    "e", c.empId(), "n", c.note()));
            if (c.empId() == null) {
                empty++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("day", c.day().toString());
                m.put("pos", c.pos());
                m.put("part", c.part());
                emptyList.add(m);
            } else {
                filled++;
            }
        }
        log.info("plan draft {} branch {} from {}: filled {}, empty {}", draftId, branchId, from, filled, empty);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftId", draftId);
        out.put("weekStart", from.toString());
        out.put("filled", filled);
        out.put("empty", empty);
        out.put("emptySlots", emptyList);
        out.put("warnings", res.warnings());
        out.put("pendingRequests", ld.pendingRequests());
        out.put("employeesUsed", ld.input().emps().size());
        out.put("slotsDefined", ld.input().slots().size());
        return out;
    }

    // ============================================================ чтение черновика

    private Map<String, Object> draftRow(long draftId) {
        List<Map<String, Object>> r = jdbc.queryForList("""
            SELECT d.id, d.branch_id AS "branchId", b.title AS "branchTitle", to_char(d.week_start, 'YYYY-MM-DD') AS "weekStart",
                   d.status, to_char(d.published_at AT TIME ZONE 'Asia/Almaty', 'YYYY-MM-DD HH24:MI') AS "publishedAt"
            FROM plan_draft d JOIN ext_branch b ON b.id = d.branch_id WHERE d.id = :d
            """, p("d", draftId));
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Нет такого черновика");
        return r.get(0);
    }

    public List<Map<String, Object>> drafts(long branchId) {
        return jdbc.queryForList("""
            SELECT d.id, to_char(d.week_start, 'YYYY-MM-DD') AS "weekStart", d.status,
                   (SELECT count(*) FROM plan_draft_shift s WHERE s.draft_id = d.id) AS "cells",
                   (SELECT count(*) FROM plan_draft_shift s WHERE s.draft_id = d.id AND s.employee_id IS NULL) AS "empty"
            FROM plan_draft d WHERE d.branch_id = :b AND d.status <> 'ARCHIVED' ORDER BY d.week_start DESC, d.id DESC LIMIT 20
            """, p("b", branchId));
    }

    public Map<String, Object> draft(long draftId) {
        Map<String, Object> head = new LinkedHashMap<>(draftRow(draftId));
        long branchId = ((Number) head.get("branchId")).longValue();
        LocalDate from = LocalDate.parse((String) head.get("weekStart"));
        head.put("shifts", jdbc.queryForList("""
            SELECT s.id, to_char(s.day, 'YYYY-MM-DD') AS "day", s.position_code AS "pos", s.part,
                   to_char(s.start_time, 'HH24:MI') AS "start", to_char(s.end_time, 'HH24:MI') AS "end",
                   s.employee_id AS "employeeId", e.full_name AS "employeeName", s.manual, s.note
            FROM plan_draft_shift s LEFT JOIN ext_employee e ON e.id = s.employee_id
            WHERE s.draft_id = :d ORDER BY s.day, s.start_time, s.position_code, s.id
            """, p("d", draftId)));
        Loaded ld = load(branchId, from, 7, List.of());
        List<Map<String, Object>> emps = new ArrayList<>();
        for (Emp e : ld.input().emps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("name", e.name());
            m.put("positions", new java.util.TreeSet<>(e.positions()));
            emps.add(m);
        }
        emps.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));
        head.put("employees", emps);
        head.put("limits", ld.blockedView());
        head.put("pendingRequests", ld.pendingRequests());
        return head;
    }

    // ============================================================ ручные правки

    private Map<String, Object> openDraft(long draftId) {
        Map<String, Object> h = draftRow(draftId);
        if (!"DRAFT".equals(h.get("status"))) throw new ApiException(HttpStatus.CONFLICT, "Этот график уже опубликован, править нельзя");
        return h;
    }

    private Map<String, Object> shiftRow(long draftId, long shiftId) {
        List<Map<String, Object>> r = jdbc.queryForList("""
            SELECT id, to_char(day, 'YYYY-MM-DD') AS "day", position_code AS "pos", employee_id AS "eid",
                   (extract(hour FROM start_time) * 60 + extract(minute FROM start_time))::int AS "st",
                   (extract(hour FROM end_time) * 60 + extract(minute FROM end_time))::int AS "en"
            FROM plan_draft_shift WHERE id = :s AND draft_id = :d
            """, p("s", shiftId, "d", draftId));
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Нет такой клетки");
        return r.get(0);
    }

    /** Предупреждения (не запреты): за Дильназ последнее слово. */
    private List<String> checkAssign(Map<String, Object> head, long draftId, long shiftId, long employeeId, String pos,
                                     LocalDate day, int st, int en) {
        long branchId = ((Number) head.get("branchId")).longValue();
        LocalDate from = LocalDate.parse((String) head.get("weekStart"));
        List<Map<String, Object>> er = jdbc.queryForList("SELECT id, full_name, branch_id FROM ext_employee WHERE id = :e", p("e", employeeId));
        if (er.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Нет такого сотрудника");
        List<String> warn = new ArrayList<>();
        Object eb = er.get(0).get("branch_id");
        if (eb == null || ((Number) eb).longValue() != branchId) warn.add("сотрудник числится в другом филиале");

        Set<String> codes = new HashSet<>(jdbc.queryForList("SELECT position_code FROM emp_position WHERE employee_id = :e", p("e", employeeId), String.class));
        Loaded ld = load(branchId, from, 7, List.of());
        List<Hist> hist = new ArrayList<>(ld.input().history());
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT to_char(day, 'YYYY-MM-DD') AS "day", id,
                   (extract(hour FROM start_time) * 60 + extract(minute FROM start_time))::int AS "st",
                   (extract(hour FROM end_time) * 60 + extract(minute FROM end_time))::int AS "en"
            FROM plan_draft_shift WHERE draft_id = :d AND employee_id = :e AND id <> :s
            """, p("d", draftId, "e", employeeId, "s", shiftId))) {
            int s2 = ((Number) r.get("st")).intValue();
            LocalDate dd = LocalDate.parse((String) r.get("day"));
            if (dd.equals(day)) warn.add("в этот день у него уже есть другая смена");
            hist.add(new Hist(employeeId, dd, s2, normEnd(s2, ((Number) r.get("en")).intValue())));
        }
        Input in2 = new Input(from, 7, ld.input().emps(), List.of(), List.of(), ld.input().blocked(), ld.input().windows(), hist,
            ld.input().maxConsecutive(), ld.input().minRestMin());
        warn.addAll(PlanGenerator.problems(in2, new Emp(employeeId, (String) er.get(0).get("full_name"), codes), pos, day, st, en, null));
        return warn;
    }

    public Map<String, Object> assign(long draftId, long shiftId, Long employeeId) {
        Map<String, Object> head = openDraft(draftId);
        Map<String, Object> sh = shiftRow(draftId, shiftId);
        List<String> warn = new ArrayList<>();
        if (employeeId == null) {
            jdbc.update("UPDATE plan_draft_shift SET employee_id = NULL, manual = FALSE, note = NULL WHERE id = :s", p("s", shiftId));
        } else {
            int st = ((Number) sh.get("st")).intValue();
            warn = checkAssign(head, draftId, shiftId, employeeId, (String) sh.get("pos"), LocalDate.parse((String) sh.get("day")),
                st, normEnd(st, ((Number) sh.get("en")).intValue()));
            jdbc.update("UPDATE plan_draft_shift SET employee_id = :e, manual = TRUE WHERE id = :s", p("e", employeeId, "s", shiftId));
        }
        return Map.of("ok", true, "warnings", warn);
    }

    public record ShiftIn(String day, String pos, String start, String end, Long employeeId, String note) {}

    public Map<String, Object> addShift(long draftId, ShiftIn s) {
        Map<String, Object> head = openDraft(draftId);
        LocalDate day;
        try {
            day = LocalDate.parse(s.day());
        } catch (Exception e) {
            throw bad("Дата должна быть в виде ГГГГ-ММ-ДД");
        }
        LocalDate from = LocalDate.parse((String) head.get("weekStart"));
        if (day.isBefore(from) || day.isAfter(from.plusDays(6))) throw bad("День вне этой недели");
        Integer okPos = jdbc.queryForObject("SELECT count(*) FROM sched_position WHERE code = :c", p("c", s.pos()), Integer.class);
        if (okPos == null || okPos == 0) throw bad("Нет такой позиции: " + s.pos());
        int st = toMin(s.start());
        int en = normEnd(st, toMin(s.end()));
        String note = s.note() == null || s.note().isBlank() ? null : s.note().trim();
        if (note != null && note.length() > 300) throw bad("Комментарий длиннее 300 символов");
        Long id = jdbc.queryForObject("""
            INSERT INTO plan_draft_shift (draft_id, day, position_code, part, start_time, end_time, employee_id, manual, note)
            VALUES (:d, :day, :pos, :part, CAST(:st AS time), CAST(:en AS time), :e, TRUE, :n) RETURNING id
            """, p("d", draftId, "day", d(day), "pos", s.pos(), "part", partOf(st), "st", hhmm(st), "en", hhmm(en),
                "e", s.employeeId(), "n", note), Long.class);
        List<String> warn = s.employeeId() == null ? List.of()
            : checkAssign(head, draftId, id == null ? 0 : id, s.employeeId(), s.pos(), day, st, en);
        return Map.of("ok", true, "id", id == null ? 0 : id, "warnings", warn);
    }

    public int deleteShift(long draftId, long shiftId) {
        openDraft(draftId);
        return jdbc.update("DELETE FROM plan_draft_shift WHERE id = :s AND draft_id = :d", p("s", shiftId, "d", draftId));
    }

    // ============================================================ общий график (все: менеджеры и сотрудники)

    /**
     * Кто когда во сколько и на какой позиции. Время берём из нашего графика (опубликованного, иначе черновика),
     * а если там пусто — из Таймтрекера. Если наш график и Таймтрекер расходятся, отдаём ttStart/ttEnd.
     */
    public Map<String, Object> overview(long branchId, LocalDate from, int days) {
        requireBranch(branchId);
        if (days < 1 || days > 14) throw bad("Дней от 1 до 14");
        LocalDate to = from.plusDays(days - 1L);
        List<String> dayList = new ArrayList<>();
        for (int i = 0; i < days; i++) dayList.add(from.plusDays(i).toString());

        List<Map<String, Object>> people = jdbc.queryForList("""
            SELECT e.id, e.full_name AS "name",
                   CASE WHEN EXISTS (SELECT 1 FROM kln_user k WHERE k.employee_id = e.id AND k.accesses && %s) OR %s THEN 'MANAGER'
                        WHEN EXISTS (SELECT 1 FROM kln_user k WHERE k.employee_id = e.id AND 'instructor' = ANY (k.accesses)) THEN 'INSTRUCTOR'
                        ELSE 'STAFF' END AS "role"
            FROM ext_employee e WHERE e.branch_id = :b AND NOT e.is_fired
            ORDER BY e.full_name
            """.formatted(MANAGER_ACCESS, MANAGER_TITLE_SQL), p("b", branchId));

        Map<String, Map<String, Object>> tt = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT d.employee_id AS "eid", to_char(d.day, 'YYYY-MM-DD') AS "day", d.type,
                   to_char(d.plan_start, 'HH24:MI') AS "start", to_char(d.plan_end, 'HH24:MI') AS "end",
                   to_char(d.fact_in AT TIME ZONE 'Asia/Almaty', 'HH24:MI') AS "factIn",
                   extract(epoch FROM ((now() AT TIME ZONE 'Asia/Almaty') - (d.day + d.plan_start))) / 60 AS "sinceStart"
            FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id
            WHERE e.branch_id = :b AND d.day BETWEEN :f AND :t
            """, p("b", branchId, "f", d(from), "t", d(to)))) {
            tt.put(r.get("eid") + "|" + r.get("day"), r);
        }
        // на один день может быть несколько смен (например TR 12:45–16:00 и потом C 16:00–20:00): склеиваем в одну клетку
        Map<String, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT s.employee_id AS "eid", to_char(s.day, 'YYYY-MM-DD') AS "day", s.position_code AS "pos",
                   to_char(s.start_time, 'HH24:MI') AS "start", to_char(s.end_time, 'HH24:MI') AS "end", d.status
            FROM plan_draft_shift s JOIN plan_draft d ON d.id = s.draft_id
            WHERE d.branch_id = :b AND d.status IN ('PUBLISHED', 'DRAFT') AND s.employee_id IS NOT NULL AND s.day BETWEEN :f AND :t
            ORDER BY (d.status = 'PUBLISHED') DESC, d.id DESC, s.start_time, s.id
            """, p("b", branchId, "f", d(from), "t", d(to)))) {
            grouped.computeIfAbsent(r.get("eid") + "|" + r.get("day"), k -> new ArrayList<>()).add(r);
        }
        Map<String, Map<String, Object>> mine = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> en : grouped.entrySet()) {
            List<Map<String, Object>> all = en.getValue();
            Object st0 = all.get(0).get("status");
            List<Map<String, Object>> rows0 = new ArrayList<>();
            for (Map<String, Object> r : all) if (st0.equals(r.get("status"))) rows0.add(r);
            Map<String, Object> m = new LinkedHashMap<>(rows0.get(0));
            m.put("end", rows0.get(rows0.size() - 1).get("end"));
            StringBuilder codes = new StringBuilder();
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Map<String, Object> r : rows0) {
                if (codes.indexOf((String) r.get("pos")) < 0 || codes.length() == 0) {
                    if (codes.length() > 0) codes.append('+');
                    codes.append(r.get("pos"));
                }
                parts.add(Map.of("start", r.get("start"), "end", r.get("end"), "pos", r.get("pos")));
            }
            m.put("pos", codes.toString());
            if (parts.size() > 1) m.put("parts", parts);
            mine.put(en.getKey(), m);
        }

        // Таймтрекер — главный источник того, кто и когда работает (бумажный график тоже печатается из него).
        // Наш график добавляет позицию. Для сегодняшнего и прошедших дней при расхождении верим Таймтрекеру;
        // для будущих дней верим нашему опубликованному графику (его могли ещё не отправить в Таймтрекер).
        String today = LocalDate.now(ZoneId.of("Asia/Almaty")).toString();
        int noShowMin = access.intSetting("noshow_hours", 3) * 60; // сколько часов после начала смены без пробития считаем «не вышел»
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> pr : people) {
            long id = ((Number) pr.get("id")).longValue();
            List<Map<String, Object>> cells = new ArrayList<>();
            boolean any = false;
            for (String day : dayList) {
                Map<String, Object> m = mine.get(id + "|" + day);
                Map<String, Object> t2 = tt.get(id + "|" + day);
                Map<String, Object> cell = null;
                String ttType = t2 == null ? null : (String) t2.get("type");
                boolean ttAbsent = ttType != null && ttType.matches("(?i).*" + NOT_SHIFT + ".*");
                boolean ttWeekend = t2 != null && !ttAbsent && "weekend".equalsIgnoreCase(ttType);
                boolean ttShift = t2 != null && !ttAbsent && !ttWeekend && t2.get("start") != null && t2.get("end") != null;
                boolean ttWins = t2 != null && day.compareTo(today) <= 0;
                Map<String, Object> plan = null;
                if (m != null) {
                    plan = new LinkedHashMap<>();
                    plan.put("start", m.get("start"));
                    plan.put("end", m.get("end"));
                    plan.put("pos", m.get("pos"));
                    if (m.get("parts") != null) plan.put("parts", m.get("parts"));
                    plan.put("src", "PUBLISHED".equals(m.get("status")) ? "PUB" : "DRAFT");
                }
                if (ttWins) {
                    cell = new LinkedHashMap<>();
                    if (ttShift) {
                        boolean same = plan != null && plan.get("start").equals(t2.get("start")) && plan.get("end").equals(t2.get("end"));
                        cell.put("kind", "SHIFT");
                        cell.put("start", t2.get("start"));
                        cell.put("end", t2.get("end"));
                        if (plan != null) {
                            cell.put("pos", plan.get("pos"));
                            if (plan.get("parts") != null && same) cell.put("parts", plan.get("parts"));
                        }
                        cell.put("src", same ? plan.get("src") : "TT");
                        if (plan != null && !same) cell.put("plan", plan);
                        // отметка в Таймтрекере: пришёл / не вышел (нет пробития спустя N часов после начала смены)
                        if (t2.get("factIn") != null) cell.put("factIn", t2.get("factIn"));
                        else if (t2.get("sinceStart") != null && ((Number) t2.get("sinceStart")).doubleValue() > noShowMin) cell.put("noShow", true);
                    } else if (ttAbsent) {
                        cell.put("kind", "ABSENCE");
                        cell.put("note", ttType);
                        if (plan != null) cell.put("plan", plan);
                    } else {
                        cell.put("kind", "OFF");
                        if (plan != null) cell.put("plan", plan);
                    }
                } else if (m != null) {
                    cell = new LinkedHashMap<>(plan);
                    cell.put("kind", "SHIFT");
                    if (ttShift && (!m.get("start").equals(t2.get("start")) || !m.get("end").equals(t2.get("end")))) {
                        cell.put("ttStart", t2.get("start"));
                        cell.put("ttEnd", t2.get("end"));
                    }
                    if (!ttShift && (ttAbsent || ttWeekend)) cell.put("ttNote", ttType);
                } else if (ttAbsent) {
                    cell = new LinkedHashMap<>();
                    cell.put("kind", "ABSENCE");
                    cell.put("note", ttType);
                } else if (ttWeekend) {
                    cell = new LinkedHashMap<>();
                    cell.put("kind", "OFF");
                } else if (ttShift) {
                    cell = new LinkedHashMap<>();
                    cell.put("kind", "SHIFT");
                    cell.put("start", t2.get("start"));
                    cell.put("end", t2.get("end"));
                    cell.put("src", "TT");
                }
                if (cell != null && "SHIFT".equals(cell.get("kind"))) any = true;
                cells.add(cell);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", pr.get("name"));
            row.put("role", pr.get("role"));
            row.put("cells", cells);
            row.put("any", any);
            rows.add(row);
        }

        // Замены: в плане человек был, в Таймтрекере его убрали (выходной/отсутствие), а у другого появилась смена в то же время без плана.
        for (int di = 0; di < dayList.size(); di++) {
            List<Map<String, Object>> gone = new ArrayList<>(), came = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> c = ((List<Map<String, Object>>) row.get("cells")).get(di);
                if (c == null) continue;
                if (c.get("plan") != null && !"SHIFT".equals(c.get("kind"))) gone.add(row);
                else if ("SHIFT".equals(c.get("kind")) && "TT".equals(c.get("src")) && c.get("pos") == null && c.get("ttStart") == null) came.add(row);
            }
            for (Map<String, Object> g : new ArrayList<>(gone)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> gc = ((List<Map<String, Object>>) g.get("cells")).get(di);
                @SuppressWarnings("unchecked")
                Map<String, Object> gp = (Map<String, Object>) gc.get("plan");
                for (Map<String, Object> n : came) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nc = ((List<Map<String, Object>>) n.get("cells")).get(di);
                    if (nc.get("replaces") != null) continue;
                    if (gp.get("start").equals(nc.get("start")) && gp.get("end").equals(nc.get("end"))) {
                        nc.put("pos", gp.get("pos"));
                        nc.put("replaces", g.get("name"));
                        gc.put("replacedBy", n.get("name"));
                        break;
                    }
                }
            }
        }

        String synced = jdbc.queryForObject("""
            SELECT to_char(max(d.synced_at) AT TIME ZONE 'Asia/Almaty', 'DD.MM HH24:MI')
            FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id WHERE e.branch_id = :b
            """, p("b", branchId), String.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", dayList);
        out.put("rows", rows);
        out.put("ttSyncedAt", synced);
        Double ago = jdbc.queryForObject("""
            SELECT extract(epoch FROM (now() - max(d.synced_at))) / 60
            FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id WHERE e.branch_id = :b
            """, p("b", branchId), Double.class);
        out.put("ttSyncedMin", ago == null ? null : Math.round(ago));
        return out;
    }


    // ============================================================ аналитика v2: сырые смены, всё считает экран

    /** Что есть в базе: за какие даты загружены гости, график и данные Таймтрекера. Экран по этому выбирает период. */
    public Map<String, Object> analyticsCoverage(long branchId) {
        requireBranch(branchId);
        MapSqlParameterSource par = p("b", branchId);
        Map<String, Object> m = jdbc.queryForMap("""
            SELECT (SELECT to_char(min(day), 'YYYY-MM-DD') FROM sales_hour WHERE branch_id = :b) AS "salesFrom",
                   (SELECT to_char(max(day), 'YYYY-MM-DD') FROM sales_hour WHERE branch_id = :b AND guests > 1) AS "salesTo",
                   (SELECT to_char(min(s.day), 'YYYY-MM-DD') FROM plan_shift_eff s WHERE s.branch_id = :b) AS "planFrom",
                   (SELECT to_char(max(s.day), 'YYYY-MM-DD') FROM plan_shift_eff s WHERE s.branch_id = :b) AS "planTo",
                   (SELECT to_char(min(d.day), 'YYYY-MM-DD') FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id
                     WHERE e.branch_id = :b AND d.plan_start IS NOT NULL AND coalesce(d.type, '') <> 'weekend') AS "ttFrom",
                   (SELECT to_char(max(d.day), 'YYYY-MM-DD') FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id
                     WHERE e.branch_id = :b AND d.plan_start IS NOT NULL AND coalesce(d.type, '') <> 'weekend') AS "ttTo"
            """, par);
        return new LinkedHashMap<>(m);
    }

    private static int minutesBetween(String start, String end) {
        int a = Integer.parseInt(start.substring(0, 2)) * 60 + Integer.parseInt(start.substring(3, 5));
        int b = end.equals("23:59") ? 1440 : Integer.parseInt(end.substring(0, 2)) * 60 + Integer.parseInt(end.substring(3, 5));
        int d = b - a;
        if (d <= 0) d += 1440;
        return d;
    }

    /**
     * Смены сотрудников (без менеджеров) за период в том виде, как их показывает «Общий график»:
     * Таймтрекер главный, позиция из нашего графика, замены, «не вышел». Плюс гости/выручка по часам и правило перерывов.
     * Позиция может быть пустой (в нашем графике смены нет, например за прошлые месяцы): тогда экран берёт позицию по умолчанию.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyticsShifts(long branchId, LocalDate from, LocalDate to, boolean includeDrafts) {
        requireBranch(branchId);
        if (to.isBefore(from)) throw bad("Конец периода раньше начала");
        if (from.plusDays(62).isBefore(to)) throw bad("Период не больше 62 дней");

        List<Map<String, Object>> shifts = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (LocalDate c = from; !c.isAfter(to); c = c.plusDays(14)) {
            int n = (int) Math.min(14, java.time.temporal.ChronoUnit.DAYS.between(c, to) + 1);
            Map<String, Object> ov = overview(branchId, c, n);
            List<String> days = (List<String>) ov.get("days");
            for (Map<String, Object> row : (List<Map<String, Object>>) ov.get("rows")) {
                List<Map<String, Object>> cells = (List<Map<String, Object>>) row.get("cells");
                for (int i = 0; i < cells.size(); i++) {
                    Map<String, Object> cell = cells.get(i);
                    if (cell == null || !"SHIFT".equals(cell.get("kind"))) continue;
                    if (!includeDrafts && "DRAFT".equals(cell.get("src"))) continue;
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) cell.get("parts");
                    List<Map<String, Object>> list = new ArrayList<>();
                    if (parts != null && parts.size() > 1) list.addAll(parts);
                    else list.add(cell);
                    for (Map<String, Object> pt : list) {
                        String st = (String) pt.get("start"), en = (String) pt.get("end");
                        if (st == null || en == null) continue;
                        Map<String, Object> sh = new LinkedHashMap<>();
                        sh.put("day", days.get(i));
                        sh.put("emp", row.get("id"));
                        sh.put("name", row.get("name"));
                        sh.put("role", row.get("role"));
                        sh.put("pos", pt.get("pos"));
                        sh.put("start", st);
                        sh.put("end", en);
                        sh.put("min", minutesBetween(st, en));
                        sh.put("src", cell.get("src"));
                        if (Boolean.TRUE.equals(cell.get("noShow"))) sh.put("noShow", true);
                        if (cell.get("factIn") != null) sh.put("factIn", cell.get("factIn"));
                        if (cell.get("replaces") != null) sh.put("replaces", cell.get("replaces"));
                        shifts.add(sh);
                        ids.add(((Number) row.get("id")).longValue());
                    }
                }
            }
        }

        // какие позиции умеет каждый (чтобы угадать позицию, если её нет в графике)
        Map<Long, List<String>> can = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT ep.employee_id AS \"eid\", ep.position_code AS \"code\" FROM emp_position ep WHERE ep.employee_id IN (:ids) ORDER BY ep.position_code",
                    p("ids", ids))) {
                can.computeIfAbsent(((Number) r.get("eid")).longValue(), k -> new ArrayList<>()).add((String) r.get("code"));
            }
        }
        Map<String, Object> canOut = new LinkedHashMap<>();
        can.forEach((k, v) -> canOut.put(String.valueOf(k), v));

        MapSqlParameterSource par = p("b", branchId, "f", d(from), "t", d(to));
        Map<String, int[]> gcByDay = new LinkedHashMap<>();
        for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) gcByDay.put(x.toString(), new int[24]);
        long gcSum = 0;
        double sumSales = 0;
        Map<String, Double> amountByDay = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT to_char(day, 'YYYY-MM-DD') AS "day", hour AS "h", guests AS "g", sales AS "s"
            FROM sales_hour WHERE branch_id = :b AND day BETWEEN :f AND :t
            """, par)) {
            int[] a = gcByDay.get((String) r.get("day"));
            if (a == null) continue;
            int g = ((Number) r.get("g")).intValue();
            a[((Number) r.get("h")).intValue()] = g;
            gcSum += g;
            sumSales += ((Number) r.get("s")).doubleValue();
            amountByDay.merge((String) r.get("day"), ((Number) r.get("s")).doubleValue(), Double::sum);
        }
        List<Map<String, Object>> byDay = new ArrayList<>();
        for (Map.Entry<String, int[]> en : gcByDay.entrySet()) {
            List<Integer> hrs = new ArrayList<>();
            int tot = 0;
            for (int v : en.getValue()) { hrs.add(v); tot += v; }
            byDay.add(Map.of("day", en.getKey(), "hours", hrs, "total", tot, "amount", Math.round(amountByDay.getOrDefault(en.getKey(), 0.0))));
        }
        Map<String, Object> sales = new LinkedHashMap<>();
        sales.put("guests", gcSum);
        sales.put("amount", Math.round(sumSales));
        sales.put("avgCheck", gcSum > 0 ? Math.round(sumSales / gcSum) : 0);
        sales.put("byDay", byDay);

        List<Map<String, Object>> rule = jdbc.queryForList(
            "SELECT min_hours AS \"minHours\", (SELECT coalesce(sum(x), 0) FROM unnest(minutes) x) AS \"total\", array_to_string(minutes, '+') AS \"text\" FROM plan_break_rule ORDER BY min_hours",
            new MapSqlParameterSource());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("includeDrafts", includeDrafts);
        out.put("shifts", shifts);
        out.put("can", canOut);
        out.put("sales", sales);
        out.put("breakRule", rule);
        return out;
    }

    // ============================================================ аналитика

    /**
     * Часы, перерывы (непроизводственные часы), позиции и загрузка по часам за период.
     * Источник — опубликованные недели; черновики подмешиваются только для недель, где публикации ещё нет, и только по просьбе.
     * Менеджеры в аналитику не входят (у них свой график).
     */
    public Map<String, Object> analytics(long branchId, LocalDate from, LocalDate to, boolean includeDrafts) {
        requireBranch(branchId);
        if (to.isBefore(from)) throw bad("Конец периода раньше начала");
        if (from.plusDays(62).isBefore(to)) throw bad("Период не больше 62 дней");

        MapSqlParameterSource par = p("b", branchId, "f", d(from), "t", d(to), "f1", d(from.minusDays(1)), "dr", includeDrafts);
        String pick = """
              (s.status = 'PUBLISHED' OR (CAST(:dr AS boolean) AND NOT EXISTS
                 (SELECT 1 FROM plan_draft d2 WHERE d2.branch_id = s.branch_id AND d2.week_start = s.week_start AND d2.status = 'PUBLISHED')))
            """;
        String notManager = "NOT (EXISTS (SELECT 1 FROM kln_user k WHERE k.employee_id = e.id AND k.accesses && " + MANAGER_ACCESS + ") OR " + MANAGER_TITLE_SQL + ")";

        List<Map<String, Object>> emps = jdbc.queryForList("""
            SELECT e.id, e.full_name AS "name",
                   count(s.id) AS "shifts", coalesce(sum(s.dur_min), 0) AS "minutes",
                   coalesce(sum(plan_break_min(s.dur_min)), 0) AS "breakMin",
                   count(DISTINCT s.day) AS "days",
                   count(s.id) FILTER (WHERE s.part = 'MORNING') AS "morning",
                   count(s.id) FILTER (WHERE s.part = 'MID') AS "mid",
                   count(s.id) FILTER (WHERE s.part = 'EVENING') AS "evening",
                   count(s.id) FILTER (WHERE s.part = 'NIGHT') AS "night",
                   count(s.id) FILTER (WHERE extract(isodow FROM s.day) IN (6, 7)) AS "weekend"
            FROM ext_employee e
            LEFT JOIN plan_shift_eff s ON s.employee_id = e.id AND s.branch_id = :b AND s.day BETWEEN :f AND :t AND
            """ + pick + """
            WHERE e.branch_id = :b AND NOT e.is_fired AND
            """ + notManager + """
              AND (s.id IS NOT NULL OR EXISTS (SELECT 1 FROM emp_position ep WHERE ep.employee_id = e.id))
            GROUP BY e.id, e.full_name
            ORDER BY coalesce(sum(s.dur_min), 0) DESC, e.full_name
            """, par);

        Map<Long, List<Map<String, Object>>> byPos = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT s.employee_id AS "eid", s.position_code AS "pos", count(*) AS "shifts", sum(s.dur_min) AS "minutes",
                   sum(plan_break_min(s.dur_min)) AS "breakMin",
                   string_agg(s.dur_min::text, ',' ORDER BY s.day, s.start_min) AS "durs"
            FROM plan_shift_eff s
            WHERE s.branch_id = :b AND s.day BETWEEN :f AND :t AND
            """ + pick + """
            GROUP BY s.employee_id, s.position_code
            ORDER BY sum(s.dur_min) DESC
            """, par)) {
            byPos.computeIfAbsent(((Number) r.get("eid")).longValue(), k -> new ArrayList<>()).add(r);
        }
        Map<Long, List<String>> allowed = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT ep.employee_id AS "eid", ep.position_code AS "code" FROM emp_position ep
            JOIN ext_employee e ON e.id = ep.employee_id WHERE e.branch_id = :b ORDER BY ep.position_code
            """, par)) {
            allowed.computeIfAbsent(((Number) r.get("eid")).longValue(), k -> new ArrayList<>()).add((String) r.get("code"));
        }
        for (Map<String, Object> e : emps) {
            long id = ((Number) e.get("id")).longValue();
            List<Map<String, Object>> bp = byPos.getOrDefault(id, List.of());
            List<String> can = allowed.getOrDefault(id, List.of());
            e.put("byPos", bp);
            e.put("can", can);
            List<String> off = new ArrayList<>();
            for (Map<String, Object> x : bp) {
                String code = (String) x.get("pos");
                if (!code.equals("TR") && !code.equals("TRN") && !can.contains(code)) off.add(code);
            }
            e.put("offPos", off);
        }

        List<Map<String, Object>> positions = jdbc.queryForList("""
            SELECT s.position_code AS "pos", count(*) AS "shifts", count(DISTINCT s.employee_id) AS "people",
                   sum(s.dur_min) AS "minutes", sum(plan_break_min(s.dur_min)) AS "breakMin"
            FROM plan_shift_eff s
            JOIN ext_employee e ON e.id = s.employee_id
            WHERE s.branch_id = :b AND s.day BETWEEN :f AND :t AND
            """ + pick + " AND " + notManager + """

            GROUP BY s.position_code ORDER BY sum(s.dur_min) DESC
            """, par);

        // сколько человек в смене в каждый час (на hh:30), включая переходящие через полночь смены предыдущего дня
        Map<String, int[]> heat = new LinkedHashMap<>();
        for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) heat.put(x.toString(), new int[24]);
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT to_char(g.day, 'YYYY-MM-DD') AS "day", h.h AS "h", count(*) AS "n"
            FROM generate_series(CAST(:f AS timestamp), CAST(:t AS timestamp), interval '1 day') g(day)
            CROSS JOIN generate_series(0, 23) h(h)
            JOIN plan_shift_eff s ON s.branch_id = :b AND s.day BETWEEN :f1 AND :t
                 AND (s.day + s.start_time) <= (g.day + h.h * interval '1 hour' + interval '30 minutes')
                 AND (g.day + h.h * interval '1 hour' + interval '30 minutes') < (s.day + s.start_time + s.dur_min * interval '1 minute')
                 AND
            """ + pick + """
            JOIN ext_employee e ON e.id = s.employee_id AND
            """ + notManager + """

            GROUP BY g.day, h.h
            """, par)) {
            int[] a = heat.get((String) r.get("day"));
            if (a != null) a[((Number) r.get("h")).intValue()] = ((Number) r.get("n")).intValue();
        }
        List<Map<String, Object>> heatOut = new ArrayList<>();
        for (Map.Entry<String, int[]> en : heat.entrySet()) {
            List<Integer> hrs = new ArrayList<>();
            for (int v : en.getValue()) hrs.add(v);
            heatOut.add(Map.of("day", en.getKey(), "hours", hrs));
        }

        long minutes = 0, brk = 0, shifts = 0;
        for (Map<String, Object> e : emps) {
            minutes += ((Number) e.get("minutes")).longValue();
            brk += ((Number) e.get("breakMin")).longValue();
            shifts += ((Number) e.get("shifts")).longValue();
        }
        Integer draftWeeks = jdbc.queryForObject("""
            SELECT count(DISTINCT s.week_start) FROM plan_shift_eff s
            WHERE s.branch_id = :b AND s.day BETWEEN :f AND :t AND s.status = 'DRAFT'
              AND NOT EXISTS (SELECT 1 FROM plan_draft d2 WHERE d2.branch_id = s.branch_id AND d2.week_start = s.week_start AND d2.status = 'PUBLISHED')
            """, par, Integer.class);

        // гости (GC) и выручка из загруженного отчёта «Почасовая выручка»
        Map<String, int[]> gcByDay = new LinkedHashMap<>();
        for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) gcByDay.put(x.toString(), new int[24]);
        long gcSum = 0;
        double gcSales = 0;
        int gcDays = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
            SELECT to_char(day, 'YYYY-MM-DD') AS "day", hour AS "h", guests AS "g", sales AS "s"
            FROM sales_hour WHERE branch_id = :b AND day BETWEEN :f AND :t
            """, par)) {
            int[] a = gcByDay.get((String) r.get("day"));
            if (a == null) continue;
            int g = ((Number) r.get("g")).intValue();
            a[((Number) r.get("h")).intValue()] = g;
            gcSum += g;
            gcSales += ((Number) r.get("s")).doubleValue();
        }
        for (int[] a : gcByDay.values()) { int t = 0; for (int v : a) t += v; if (t > 1) gcDays++; }
        List<Map<String, Object>> gcOut = new ArrayList<>();
        for (Map.Entry<String, int[]> en : gcByDay.entrySet()) {
            List<Integer> hrs = new ArrayList<>();
            for (int v : en.getValue()) hrs.add(v);
            gcOut.add(Map.of("day", en.getKey(), "hours", hrs));
        }
        Map<String, Object> salesOut = new LinkedHashMap<>();
        salesOut.put("has", gcSum > 0);
        salesOut.put("guests", gcSum);
        salesOut.put("amount", Math.round(gcSales));
        salesOut.put("days", gcDays);
        salesOut.put("avgCheck", gcSum > 0 ? Math.round(gcSales / gcSum) : 0);
        salesOut.put("byDay", gcOut);
        salesOut.put("lastImport", jdbc.queryForList("""
            SELECT file_name AS "file", restaurant, to_char(from_day, 'YYYY-MM-DD') AS "from", to_char(to_day, 'YYYY-MM-DD') AS "to",
                   to_char(imported_at AT TIME ZONE 'Asia/Almaty', 'DD.MM HH24:MI') AS "at"
            FROM sales_import WHERE branch_id = :b ORDER BY id DESC LIMIT 1
            """, par).stream().findFirst().orElse(null));

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("people", emps.stream().filter(e -> ((Number) e.get("shifts")).intValue() > 0).count());
        total.put("shifts", shifts);
        total.put("minutes", minutes);
        total.put("breakMin", brk);
        total.put("prodMin", minutes - brk);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("includeDrafts", includeDrafts);
        out.put("draftWeeksOnly", draftWeeks == null ? 0 : draftWeeks);
        out.put("total", total);
        out.put("employees", emps);
        out.put("positions", positions);
        out.put("heat", heatOut);
        out.put("sales", salesOut);
        out.put("breakRule", jdbc.queryForList(
            "SELECT min_hours AS \"minHours\", array_to_string(minutes, '+') AS \"minutes\" FROM plan_break_rule ORDER BY min_hours", new MapSqlParameterSource()));
        return out;
    }

    // ============================================================ публикация

    /**
     * Фиксирует черновик как опубликованный график. Запись в Таймтрекер пока НЕ выполняется:
     * сначала нужен перехваченный запрос смены выходного/рабочего дня (см. SETUP-PLANNER.md).
     */
    @Transactional
    public Map<String, Object> publish(UserPrincipal u, long draftId, boolean confirmEmpty) {
        Map<String, Object> head = openDraft(draftId);
        Integer empty = jdbc.queryForObject("SELECT count(*) FROM plan_draft_shift WHERE draft_id = :d AND employee_id IS NULL",
            p("d", draftId), Integer.class);
        int e = empty == null ? 0 : empty;
        if (e > 0 && !confirmEmpty) {
            throw new ApiException(HttpStatus.CONFLICT, "Не закрыто слотов: " + e + ". Подтвердите публикацию с пустыми слотами или закройте их");
        }
        jdbc.update("""
            UPDATE plan_draft SET status = 'ARCHIVED'
            WHERE branch_id = :b AND week_start = CAST(:w AS date) AND status = 'PUBLISHED'
            """, p("b", head.get("branchId"), "w", head.get("weekStart")));
        jdbc.update("UPDATE plan_draft SET status = 'PUBLISHED', published_by = :u, published_at = now() WHERE id = :d",
            p("u", u.id(), "d", draftId));
        log.info("plan draft {} published by user {}, empty slots {}", draftId, u.id(), e);
        return Map.of("ok", true, "emptySlots", e, "ttWritten", false,
            "message", "График опубликован на сайте. В Таймтрекер пока не записан — внесите смены там или дождитесь включения записи.");
    }
}