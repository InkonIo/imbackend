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

    private final NamedParameterJdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Value("${timetracker.token:}")
    private String token;
    @Value("${timetracker.base-url:https://api.timetracker.kz}")
    private String baseUrl;
    @Value("${timetracker.company-id:230}")
    private long companyId;

    public TimetrackerService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Полная синхронизация сотрудников: идёт по всем страницам. */
    public Map<String, Object> syncEmployees() {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Не задан TIMETRACKER_TOKEN (переменная окружения бэка)");
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
        String url = baseUrl + "/api/v1/companies/" + companyId + "/users"
            + "?filter%5BisFired%5D=not:undefined&with%5Bbranches%5D&with%5Bdepartment%5D&with%5Bposition%5D"
            + "&page=" + page + "&sortBy%5B%5D=surname&sortBy%5B%5D=firstname";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token.trim().replaceFirst("(?i)^bearer\\s+", ""))
                .header("Accept", "application/json")
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Таймтрекер отклонил токен (" + resp.statusCode() + "). Войди на сайт заново и обнови TIMETRACKER_TOKEN");
            }
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Таймтрекер ответил " + resp.statusCode());
            }
            return resp.body();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Запрос прерван");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось достучаться до таймтрекера: " + e.getMessage());
        }
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
