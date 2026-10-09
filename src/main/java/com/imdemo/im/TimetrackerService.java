package com.imdemo.im;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Забирает сотрудников из timetracker.kz (GET, Bearer-токен из TIMETRACKER_TOKEN)
 * и кладёт в ext_* таблицы. JSON разбирает сам Postgres (jsonb), без Jackson.
 */
@Service
public class TimetrackerService {

    private static final String S_BRANCH = """
        INSERT INTO ext_branch(id, company_id, title, head_id)
        SELECT DISTINCT ON ((b->>'id')::bigint) (b->>'id')::bigint, (b->>'companyId')::bigint, b->>'title', (b->>'headId')::bigint
        FROM jsonb_array_elements(CAST(:body AS jsonb)->'data') u, jsonb_array_elements(coalesce(u->'branches', '[]'::jsonb)) b
        ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, head_id = EXCLUDED.head_id, synced_at = now()
        """;

    private static final String S_DEPT = """
        INSERT INTO ext_department(id, company_id, title, work_days)
        SELECT DISTINCT ON ((u->'department'->>'id')::bigint) (u->'department'->>'id')::bigint, (u->'department'->>'companyId')::bigint,
               u->'department'->>'title', u->'department'->>'workDays'
        FROM jsonb_array_elements(CAST(:body AS jsonb)->'data') u
        WHERE jsonb_typeof(u->'department') = 'object'
        ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, work_days = EXCLUDED.work_days, synced_at = now()
        """;

    private static final String S_POS = """
        INSERT INTO ext_position(id, company_id, title)
        SELECT DISTINCT ON ((u->'position'->>'id')::bigint) (u->'position'->>'id')::bigint, (u->'position'->>'companyId')::bigint,
               u->'position'->>'title'
        FROM jsonb_array_elements(CAST(:body AS jsonb)->'data') u
        WHERE jsonb_typeof(u->'position') = 'object'
        ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, synced_at = now()
        """;

    private static final String S_EMP = """
        INSERT INTO ext_employee(id, company_id, department_id, branch_id, position_id, iin, full_name, firstname, surname,
                                 patronymic, phone, hired_date, fired_date, is_fired, is_registered, raw)
        SELECT (u->>'id')::bigint, (u->>'companyId')::bigint, (u->>'departmentId')::bigint,
               (u->'branches'->0->>'id')::bigint, (u->>'positionId')::bigint, u->>'iin',
               coalesce(nullif(btrim(u->>'fullName'), ''), btrim(concat_ws(' ', u->>'surname', u->>'firstname'))),
               u->>'firstname', u->>'surname', nullif(u->>'patronymic', ''), nullif(u->>'phone', ''),
               (u->>'hiredDate')::timestamptz, (u->>'firedDate')::timestamptz,
               coalesce((u->>'isFired')::boolean, false), (u->>'isRegistered')::boolean,
               u - 'accesses' - 'locationPoints'
        FROM jsonb_array_elements(CAST(:body AS jsonb)->'data') u
        ON CONFLICT (id) DO UPDATE SET
          company_id = EXCLUDED.company_id, department_id = EXCLUDED.department_id, branch_id = EXCLUDED.branch_id,
          position_id = EXCLUDED.position_id, iin = EXCLUDED.iin, full_name = EXCLUDED.full_name,
          firstname = EXCLUDED.firstname, surname = EXCLUDED.surname, patronymic = EXCLUDED.patronymic,
          phone = EXCLUDED.phone, hired_date = EXCLUDED.hired_date, fired_date = EXCLUDED.fired_date,
          is_fired = EXCLUDED.is_fired, is_registered = EXCLUDED.is_registered, raw = EXCLUDED.raw, synced_at = now()
        """;

    private static final String S_DAYS = """
        INSERT INTO ext_sheet_day(employee_id, day, sheet_id, type, plan_start, plan_end, fact_in, fact_out, worked_min, raw)
        SELECT (u->>'id')::bigint,
               ((s.value->>'date')::timestamptz AT TIME ZONE 'Asia/Almaty')::date,
               (s.value->>'id')::bigint, s.value->>'type',
               nullif(s.value->>'startAt', '')::time, nullif(s.value->>'endAt', '')::time,
               (s.value->>'enteredAt')::timestamptz, (s.value->>'leaveAt')::timestamptz,
               coalesce((s.value->>'h')::int, 0) * 60 + coalesce((s.value->>'m')::int, 0),
               s.value - 'visits' - 'breaks' - 'locationPoints'
        FROM jsonb_array_elements(CAST(:body AS jsonb)->'data') u,
             jsonb_each(CASE WHEN jsonb_typeof(u->'workSheet') = 'object' THEN u->'workSheet' ELSE '{}'::jsonb END) s
        ON CONFLICT (employee_id, day) DO UPDATE SET
          sheet_id = EXCLUDED.sheet_id, type = EXCLUDED.type, plan_start = EXCLUDED.plan_start, plan_end = EXCLUDED.plan_end,
          fact_in = EXCLUDED.fact_in, fact_out = EXCLUDED.fact_out, worked_min = EXCLUDED.worked_min,
          raw = EXCLUDED.raw, synced_at = now()
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Value("${timetracker.token:}")
    private String token;
    @Value("${timetracker.base-url:https://api.timetracker.kz}")
    private String baseUrl;
    @Value("${timetracker.company-id:230}")
    private long companyId;
    @Value("${timetracker.login:}")
    private String login;
    @Value("${timetracker.password:}")
    private String password;
    @Value("${timetracker.login-field:login}")
    private String loginField;
    @Value("${timetracker.password-field:password}")
    private String passwordField;

    /** Токен, полученный входом по логину; живёт в памяти до перезапуска или до 401. */
    private volatile String cachedToken;

    public TimetrackerService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Полная синхронизация сотрудников: идёт по всем страницам. */
    public Map<String, Object> syncEmployees() {
        if (!hasCredentials() && (token == null || token.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                missingMsg());
        }
        int page = 1, pages = 1, saved = 0;
        while (page <= pages) {
            String body = fetchUsers(page);
            MapSqlParameterSource p = new MapSqlParameterSource("body", body);
            jdbc.update(S_BRANCH, p);
            jdbc.update(S_DEPT, p);
            jdbc.update(S_POS, p);
            saved += jdbc.update(S_EMP, p);
            Integer total = jdbc.queryForObject(
                "SELECT coalesce((CAST(:body AS jsonb)->'pagination'->>'pages')::int, 1)", p, Integer.class);
            pages = total == null ? 1 : total;
            page++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pages", pages);
        r.put("saved", saved);
        r.put("total", count());
        return r;
    }

    private String fetchUsers(int page) {
        // [ ] кодируем сами: java.net.URI их не принимает
        return getJson(baseUrl + "/api/v1/companies/" + companyId + "/users"
            + "?filter%5BisFired%5D=not:undefined&with%5Bbranches%5D&with%5Bdepartment%5D&with%5Bposition%5D"
            + "&page=" + page + "&sortBy%5B%5D=surname&sortBy%5B%5D=firstname");
    }

    private String fetchSheet(java.time.LocalDate from, java.time.LocalDate to, int page) {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
        return getJson(baseUrl + "/api/v1/companies/" + companyId + "/users"
            + "?filter%5BisFired%5D=no&with%5BworkSheet%5D&with%5Bbranches%5D&with%5Bdepartment%5D&with%5Bposition%5D"
            + "&year=" + f.format(from) + "&to=" + f.format(to) + "&needPeriod=true"
            + "&page=" + page + "&size=100&sortBy%5B%5D=surname&sortBy%5B%5D=firstname");
    }

    private String getJson(String url) {
        HttpResponse<String> resp = get(url, currentToken());
        if ((resp.statusCode() == 401 || resp.statusCode() == 403) && hasCredentials()) {
            cachedToken = null;               // токен протух: входим заново и пробуем один раз
            resp = get(url, currentToken());
        }
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Таймтрекер отклонил токен (" + resp.statusCode() + "). Задай TIMETRACKER_LOGIN и TIMETRACKER_PASSWORD или новый токен");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Таймтрекер ответил " + resp.statusCode());
        }
        return resp.body();
    }

    private String missingMsg() {
        return "Бэк не видит учётные данные Таймтрекера. Логин: " + (login == null || login.isBlank() ? "НЕТ" : "есть")
            + ", пароль: " + (password == null || password.isBlank() ? "НЕТ" : "есть")
            + ", токен: " + (token == null || token.isBlank() ? "НЕТ" : "есть")
            + ". Переменные TIMETRACKER_LOGIN / TIMETRACKER_PASSWORD должны быть в окружении процесса бэка (Run Configuration или export), .env Spring не читает";
    }

    private boolean hasCredentials() {
        return login != null && !login.isBlank() && password != null && !password.isBlank();
    }

    private String currentToken() {
        String t = cachedToken;
        if (t != null) return t;
        if (hasCredentials()) {
            t = loginToTimetracker();
            cachedToken = t;
            return t;
        }
        return token.trim().replaceFirst("(?i)^bearer\\s+", "");
    }

    private String loginToTimetracker() {
        String json = "{" + q(loginField) + ":" + q(login.trim()) + "," + q(passwordField) + ":" + q(password) + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                String body = resp.body() == null ? "" : resp.body();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Вход в Таймтрекер не удался ("
                    + resp.statusCode() + "): " + body.substring(0, Math.min(body.length(), 200)));
            }
            String t;
            try {
                t = jdbc.getJdbcTemplate().queryForObject("""
                    SELECT coalesce(j->>'access_token', j->>'accessToken', j->>'token',
                                    j->'data'->'token'->>'accessToken', j->'data'->'token'->>'access_token',
                                    j->'data'->>'access_token', j->'data'->>'accessToken', j->'data'->>'token')
                    FROM (SELECT CAST(? AS jsonb) j) x
                    """, String.class, resp.body());
            } catch (Exception e) {
                t = null;
            }
            if (t == null || t.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Вход прошёл, но токен в ответе не найден. Нужно посмотреть формат ответа login");
            }
            return t;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Запрос прерван");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось войти в таймтрекер: " + e.getMessage());
        }
    }

    private static String q(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private HttpResponse<String> get(String url, String bearer) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Запрос прерван");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось достучаться до таймтрекера: " + e.getMessage());
        }
    }

    /** Ответ Таймтрекера на запись: статус и тело (для журнала). */
    public record TtResp(int status, String body) {}

    /** GET любого пути API (с уже закодированными скобками). Бросает ошибку при не-2xx. */
    public String ttGet(String pathAndQuery) {
        return getJson(baseUrl + pathAndQuery);
    }

    /** PATCH JSON. При 401/403 один раз входит заново. Не бросает ошибку на не-2xx, чтобы вызывающий записал её в журнал. */
    public TtResp ttPatch(String path, String json) {
        TtResp r = patchOnce(baseUrl + path, json, currentToken());
        if ((r.status() == 401 || r.status() == 403) && hasCredentials()) {
            cachedToken = null;
            r = patchOnce(baseUrl + path, json, currentToken());
        }
        return r;
    }

    private TtResp patchOnce(String url, String json, String bearer) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new TtResp(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Запрос прерван");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось достучаться до таймтрекера: " + e.getMessage());
        }
    }

    /** Загружает план и факт по дням за месяц (YYYY-MM). Заодно обновляет сотрудников. */
    public Map<String, Object> syncSchedule(String month) {
        if (!hasCredentials() && (token == null || token.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                missingMsg());
        }
        java.time.YearMonth ym = parseMonth(month);
        java.time.LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        int page = 1, pages = 1, days = 0;
        while (page <= pages) {
            MapSqlParameterSource p = new MapSqlParameterSource("body", fetchSheet(from, to, page));
            jdbc.update(S_BRANCH, p);
            jdbc.update(S_DEPT, p);
            jdbc.update(S_POS, p);
            jdbc.update(S_EMP, p);
            days += jdbc.update(S_DAYS, p);
            Integer total = jdbc.queryForObject(
                "SELECT coalesce((CAST(:body AS jsonb)->'pagination'->>'pages')::int, 1)", p, Integer.class);
            pages = total == null ? 1 : total;
            page++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", ym.toString());
        r.put("pages", pages);
        r.put("days", days);
        return r;
    }

    private static java.time.YearMonth parseMonth(String month) {
        try {
            if (month != null && !month.isBlank()) return java.time.YearMonth.parse(month.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month должен быть вида 2026-10");
        }
        return java.time.YearMonth.now(java.time.ZoneId.of("Asia/Almaty"));
    }

    /** Плоский список дней месяца по сотрудникам (без ИИН). */
    public Map<String, Object> schedule(String month, Long branchId) {
        java.time.YearMonth ym = parseMonth(month);
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("from", java.sql.Date.valueOf(ym.atDay(1)))
            .addValue("to", java.sql.Date.valueOf(ym.atEndOfMonth()))
            .addValue("branch", branchId);
        List<Map<String, Object>> emps = jdbc.queryForList("""
            SELECT e.id, e.full_name AS "fullName", pos.title AS "position"
            FROM ext_employee e LEFT JOIN ext_position pos ON pos.id = e.position_id
            WHERE NOT e.is_fired AND (CAST(:branch AS bigint) IS NULL OR e.branch_id = CAST(:branch AS bigint))
            ORDER BY e.full_name
            """, p);
        List<Map<String, Object>> cells = jdbc.queryForList("""
            SELECT d.employee_id AS "employeeId", extract(day FROM d.day)::int AS "day", d.type,
                   to_char(d.plan_start, 'HH24:MI') AS "planStart", to_char(d.plan_end, 'HH24:MI') AS "planEnd",
                   to_char(d.fact_in AT TIME ZONE 'Asia/Almaty', 'HH24:MI') AS "factIn",
                   to_char(d.fact_out AT TIME ZONE 'Asia/Almaty', 'HH24:MI') AS "factOut",
                   d.worked_min AS "workedMin", d.edited,
                   d.tt_type AS "ttType", to_char(d.tt_start, 'HH24:MI') AS "ttStart", to_char(d.tt_end, 'HH24:MI') AS "ttEnd"
            FROM ext_sheet_day_eff d JOIN ext_employee e ON e.id = d.employee_id
            WHERE d.day BETWEEN :from AND :to AND NOT e.is_fired
              AND (CAST(:branch AS bigint) IS NULL OR e.branch_id = CAST(:branch AS bigint))
            """, p);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", ym.toString());
        r.put("daysInMonth", ym.lengthOfMonth());
        r.put("employees", emps);
        r.put("cells", cells);
        return r;
    }

    private static final String CTE_DAYS = """
        WITH d AS (
          SELECT e.id AS emp, e.full_name, pos.title AS position, s.day, s.type, s.plan_start, s.plan_end, s.worked_min,
                 (s.type IS DISTINCT FROM 'weekend' AND s.plan_start IS NOT NULL
                  AND coalesce(s.type, '') !~* '(vac|sick|leave|trip|holiday|celeb|before|fire|dismiss|inweekend)') AS planned,
                 (s.fact_in IS NOT NULL) AS came,
                 CASE WHEN s.fact_in IS NOT NULL AND s.plan_start IS NOT NULL
                      THEN extract(epoch FROM ((s.fact_in AT TIME ZONE 'Asia/Almaty') - (s.day + s.plan_start))) / 60 END AS late_min,
                 CASE WHEN s.fact_out IS NOT NULL AND s.plan_end IS NOT NULL AND s.plan_start IS NOT NULL
                      THEN extract(epoch FROM ((s.day + s.plan_end + CASE WHEN s.plan_end <= s.plan_start THEN interval '1 day' ELSE interval '0' END)
                                               - (s.fact_out AT TIME ZONE 'Asia/Almaty'))) / 60 END AS early_min,
                 (s.day <= (now() AT TIME ZONE 'Asia/Almaty')::date) AS past
          FROM ext_sheet_day_eff s
          JOIN ext_employee e ON e.id = s.employee_id
          LEFT JOIN ext_position pos ON pos.id = e.position_id
          WHERE s.day BETWEEN :from AND :to AND NOT e.is_fired
            AND (CAST(:branch AS bigint) IS NULL OR e.branch_id = CAST(:branch AS bigint))
        ), x AS (
          SELECT d.*,
                 (planned AND NOT came AND past AND coalesce(type, '') !~* '(vac|sick|leave|trip|holiday|celeb|before|fire|dismiss)') AS noshow,
                 (late_min > 0) AS is_late
          FROM d
        )
        """;

    /** Аналитика по сотрудникам за месяц на основе загруженного графика. */
    public Map<String, Object> analytics(String month, Long branchId) {
        java.time.YearMonth ym = parseMonth(month);
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("from", java.sql.Date.valueOf(ym.atDay(1)))
            .addValue("to", java.sql.Date.valueOf(ym.atEndOfMonth()))
            .addValue("branch", branchId);

        Map<String, Object> summary = jdbc.queryForMap(CTE_DAYS + """
            SELECT count(DISTINCT emp) FILTER (WHERE planned OR came) AS employees,
                   count(*) FILTER (WHERE planned) AS planned,
                   count(*) FILTER (WHERE planned AND past) AS "plannedPast",
                   count(*) FILTER (WHERE planned AND came) AS attended,
                   count(*) FILTER (WHERE came AND NOT planned) AS extra,
                   count(*) FILTER (WHERE is_late AND planned) AS late,
                   count(*) FILTER (WHERE noshow) AS noshow,
                   round(coalesce(sum(worked_min), 0) / 60.0, 1) AS hours,
                   round(coalesce(avg(worked_min) FILTER (WHERE worked_min > 0), 0)::numeric, 0) AS "avgShiftMin",
                   round(coalesce(avg(late_min) FILTER (WHERE is_late AND planned), 0)::numeric, 1) AS "avgLateMin",
                   count(*) FILTER (WHERE early_min > 15 AND planned) AS "earlyLeave"
            FROM x
            """, p);

        List<Map<String, Object>> perEmp = jdbc.queryForList(CTE_DAYS + """
            SELECT emp AS id, max(full_name) AS "fullName", max(position) AS position,
                   count(*) FILTER (WHERE planned) AS planned,
                   count(*) FILTER (WHERE planned AND past) AS "plannedPast",
                   count(*) FILTER (WHERE planned AND came) AS attended,
                   count(*) FILTER (WHERE came AND NOT planned) AS extra,
                   count(*) FILTER (WHERE is_late AND planned) AS late,
                   round(coalesce(sum(late_min) FILTER (WHERE is_late AND planned), 0)::numeric, 0) AS "lateMin",
                   count(*) FILTER (WHERE noshow) AS noshow,
                   count(*) FILTER (WHERE early_min > 15 AND planned) AS "earlyLeave",
                   round(coalesce(sum(worked_min), 0) / 60.0, 1) AS hours,
                   round(coalesce(avg(worked_min) FILTER (WHERE worked_min > 0), 0)::numeric, 0) AS "avgShiftMin",
                   CASE WHEN count(*) FILTER (WHERE planned AND past) = 0 THEN NULL
                        ELSE round(100.0 * count(*) FILTER (WHERE planned AND came AND NOT is_late)
                                   / count(*) FILTER (WHERE planned AND past), 0) END AS "reliability"
            FROM x
            GROUP BY emp
            HAVING count(*) FILTER (WHERE planned OR came) > 0
            ORDER BY max(full_name)
            """, p);

        List<Map<String, Object>> daily = jdbc.queryForList(CTE_DAYS + """
            SELECT extract(day FROM day)::int AS day,
                   count(*) FILTER (WHERE planned) AS planned,
                   count(*) FILTER (WHERE came) AS came,
                   count(*) FILTER (WHERE is_late AND planned) AS late,
                   count(*) FILTER (WHERE noshow) AS noshow,
                   round(coalesce(sum(worked_min), 0) / 60.0, 1) AS hours
            FROM x GROUP BY day ORDER BY day
            """, p);

        List<Map<String, Object>> weekday = jdbc.queryForList(CTE_DAYS + """
            , dd AS (
              SELECT day, extract(isodow FROM day)::int AS dow,
                     count(*) FILTER (WHERE planned) AS planned, count(*) FILTER (WHERE came) AS came,
                     count(*) FILTER (WHERE is_late AND planned) AS late
              FROM x GROUP BY day)
            SELECT dow, round(avg(planned), 1) AS planned, round(avg(came) FILTER (WHERE day <= (now() AT TIME ZONE 'Asia/Almaty')::date), 1) AS came,
                   round(avg(late) FILTER (WHERE day <= (now() AT TIME ZONE 'Asia/Almaty')::date), 1) AS late
            FROM dd GROUP BY dow ORDER BY dow
            """, p);

        List<Map<String, Object>> buckets = jdbc.queryForList(CTE_DAYS + """
            SELECT CASE WHEN late_min <= 5 THEN '1–5 мин' WHEN late_min <= 15 THEN '6–15 мин'
                        WHEN late_min <= 30 THEN '16–30 мин' ELSE 'больше 30' END AS label,
                   CASE WHEN late_min <= 5 THEN 1 WHEN late_min <= 15 THEN 2 WHEN late_min <= 30 THEN 3 ELSE 4 END AS ord,
                   count(*) AS n
            FROM x WHERE is_late AND planned GROUP BY 1, 2 ORDER BY 2
            """, p);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("month", ym.toString());
        r.put("summary", summary);
        r.put("employees", perEmp);
        r.put("daily", daily);
        r.put("weekday", weekday);
        r.put("lateBuckets", buckets);
        return r;
    }

    private long count() {
        Long n = jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM ext_employee", Long.class);
        return n == null ? 0 : n;
    }

    /** Список без ИИН и сырого JSON. */
    public Map<String, Object> list(String q, Long branchId, boolean fired) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("q", q == null ? "" : q.trim().toLowerCase().replace('ё', 'е'))
            .addValue("branch", branchId)
            .addValue("fired", fired);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT e.id, e.full_name AS "fullName", e.phone, e.is_fired AS "isFired", e.is_registered AS "isRegistered",
                   to_char(e.hired_date AT TIME ZONE 'Asia/Almaty', 'YYYY-MM-DD') AS "hiredDate",
                   to_char(e.fired_date AT TIME ZONE 'Asia/Almaty', 'YYYY-MM-DD') AS "firedDate",
                   e.branch_id AS "branchId", b.title AS "branch", p.title AS "position", d.work_days AS "workDays"
            FROM ext_employee e
            LEFT JOIN ext_branch b ON b.id = e.branch_id
            LEFT JOIN ext_position p ON p.id = e.position_id
            LEFT JOIN ext_department d ON d.id = e.department_id
            WHERE (:fired OR NOT e.is_fired)
              AND (CAST(:branch AS bigint) IS NULL OR e.branch_id = CAST(:branch AS bigint))
              AND (:q = '' OR replace(lower(e.full_name), 'ё', 'е') LIKE '%' || :q || '%' OR coalesce(e.phone, '') LIKE '%' || :q || '%')
            ORDER BY e.is_fired, e.full_name
            """, p);
        List<Map<String, Object>> branches = jdbc.getJdbcTemplate().queryForList("""
            SELECT b.id, b.title, count(e.id) FILTER (WHERE NOT e.is_fired) AS "active"
            FROM ext_branch b LEFT JOIN ext_employee e ON e.branch_id = b.id
            GROUP BY b.id, b.title ORDER BY b.title
            """);
        String synced;
        try {
            synced = jdbc.getJdbcTemplate().queryForObject(
                "SELECT to_char(max(synced_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM ext_employee", String.class);
        } catch (EmptyResultDataAccessException e) {
            synced = null;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("employees", rows);
        r.put("branches", branches);
        r.put("syncedAt", synced);
        return r;
    }
}