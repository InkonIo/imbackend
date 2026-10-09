package com.imdemo.im.ext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Забирает из kln рестораны (с типовыми сменами позиций) и сотрудников (на каких позициях могут работать).
 * Хранит без ИИН, почты и токенов. Сотрудников kln связывает с сотрудниками Таймтрекера по последним 10 цифрам телефона.
 * Позиции из kln перезаписываются при каждой синхронизации; добавленные руками (MANUAL) остаются.
 */
@Service
public class KlnSyncService {

    private static final Logger log = LoggerFactory.getLogger(KlnSyncService.class);

    private static final String PHONE_TT = "right(regexp_replace(coalesce(e.phone, ''), '\\D', '', 'g'), 10)";

    private static final String S_BRANCH = """
        INSERT INTO kln_branch (id, title, schedule_fields, fixed_shifts)
        SELECT (b->>'id')::bigint, coalesce(b->>'title', '—'),
               CASE WHEN jsonb_typeof(b->'scheduleFields') = 'array' THEN b->'scheduleFields' ELSE '[]'::jsonb END,
               CASE WHEN jsonb_typeof(b->'fixed_shifts') IN ('object', 'array') THEN b->'fixed_shifts' ELSE '{}'::jsonb END
        FROM jsonb_array_elements(
               CASE WHEN jsonb_typeof(CAST(:body AS jsonb)->'data') = 'array' THEN CAST(:body AS jsonb)->'data' ELSE '[]'::jsonb END) b
        ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, schedule_fields = EXCLUDED.schedule_fields,
                                       fixed_shifts = EXCLUDED.fixed_shifts, synced_at = now()
        """;

    private static final String S_USER = """
        INSERT INTO kln_user (id, branch_id, full_name, phone_key, is_active, ignore_sync, accesses, possibles)
        SELECT (u->>'id')::bigint, (u->>'branchId')::bigint, coalesce(nullif(btrim(u->>'fullName'), ''), '—'),
               nullif(right(regexp_replace(coalesce(u->>'phone', ''), '\\D', '', 'g'), 10), ''),
               coalesce((u->>'isActive')::boolean, true), coalesce((u->>'ignoreSync')::boolean, false),
               CASE WHEN jsonb_typeof(u->'accesses') = 'array'
                    THEN ARRAY(SELECT a->>'title' FROM jsonb_array_elements(u->'accesses') a WHERE a->>'title' IS NOT NULL)
                    ELSE '{}'::text[] END,
               CASE WHEN jsonb_typeof(u->'possibles') = 'array'
                    THEN ARRAY(SELECT jsonb_array_elements_text(u->'possibles'))
                    ELSE '{}'::text[] END
        FROM jsonb_array_elements(
               CASE WHEN jsonb_typeof(CAST(:body AS jsonb)->'data') = 'array' THEN CAST(:body AS jsonb)->'data' ELSE '[]'::jsonb END) u
        ON CONFLICT (id) DO UPDATE SET branch_id = EXCLUDED.branch_id, full_name = EXCLUDED.full_name,
            phone_key = EXCLUDED.phone_key, is_active = EXCLUDED.is_active, ignore_sync = EXCLUDED.ignore_sync,
            accesses = EXCLUDED.accesses, possibles = EXCLUDED.possibles, synced_at = now()
        """;

    /** Связь по телефону только с работающими; если телефон у нескольких работающих сразу — не связываем (неоднозначно). */
    private static final String S_MATCH = """
        UPDATE kln_user k SET employee_id = m.eid
        FROM (SELECT k2.id AS kid, min(e.id) AS eid
              FROM kln_user k2
              JOIN ext_employee e ON NOT e.is_fired AND %s = k2.phone_key
              WHERE k2.phone_key IS NOT NULL
              GROUP BY k2.id HAVING count(DISTINCT e.id) = 1) m
        WHERE k.id = m.kid
        """.formatted(PHONE_TT);

    private static final String S_POSITIONS = """
        INSERT INTO emp_position (employee_id, position_code, source)
        SELECT DISTINCT k.employee_id, p, 'KLN'
        FROM kln_user k, unnest(k.possibles) p
        WHERE k.employee_id IS NOT NULL AND k.is_active
          AND p IN (SELECT code FROM sched_position)
        ON CONFLICT (employee_id, position_code) DO NOTHING
        """;

    private final KlnClient kln;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public KlnSyncService(KlnClient kln, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.kln = kln;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Полная синхронизация. Запросов немного (рестораны + страницы сотрудников), между ними пауза ради лимита 300/мин. */
    public Map<String, Object> sync() {
        Map<String, Object> rep = new LinkedHashMap<>();
        String branches = kln.get("/branches?all=true");
        jdbc.update(S_BRANCH, new MapSqlParameterSource("body", branches));
        List<Long> ids = jdbc.getJdbcTemplate().queryForList("SELECT id FROM kln_branch ORDER BY id", Long.class);
        rep.put("branches", ids.size());

        int users = 0;
        for (long id : ids) {
            int page = 1, pages = 1;
            while (page <= pages) {
                String body = kln.get("/users?filter%5BbranchId%5D=" + id + "&with%5Baccesses%5D&page=" + page);
                users += jdbc.update(S_USER, new MapSqlParameterSource("body", body));
                Integer pc = jdbc.queryForObject(
                    "SELECT coalesce((CAST(:body AS jsonb)->'pagination'->>'pages')::int, 1)",
                    new MapSqlParameterSource("body", body), Integer.class);
                pages = pc == null ? 1 : pc;
                page++;
                KlnClient.sleep(250);
            }
        }
        rep.put("users", users);
        rep.putAll(rematch());
        log.info("kln sync: {}", rep);
        return rep;
    }

    /** Пересвязывает сотрудников и позиции по уже загруженным данным (без обращения к kln). Всё в одной транзакции. */
    public Map<String, Object> rematch() {
        Map<String, Object> rep = new LinkedHashMap<>();
        tx.executeWithoutResult(st -> {
            jdbc.getJdbcTemplate().update("UPDATE kln_user SET employee_id = NULL");
            int byPhone = jdbc.update(S_MATCH, new MapSqlParameterSource());
            // ручные связи сильнее телефона
            int manual = jdbc.getJdbcTemplate().update(
                "UPDATE kln_user k SET employee_id = l.employee_id FROM kln_link l WHERE l.kln_user_id = k.id");
            rep.put("matched", byPhone + manual);
            jdbc.getJdbcTemplate().update("DELETE FROM emp_position WHERE source = 'KLN'");
            rep.put("positions", jdbc.update(S_POSITIONS, new MapSqlParameterSource()));
        });
        // коды позиций из kln, которых нет в нашем справочнике (их нужно добавить в sched_position)
        List<String> unknown = jdbc.getJdbcTemplate().queryForList(
            "SELECT DISTINCT p FROM kln_user, unnest(possibles) p WHERE p NOT IN (SELECT code FROM sched_position) ORDER BY 1", String.class);
        rep.put("unknownPositions", unknown);
        // не нашлись в Таймтрекере — только по ресторанам, которые уже привязаны
        List<String> lost = jdbc.getJdbcTemplate().queryForList("""
            SELECT k.full_name FROM kln_user k JOIN kln_branch b ON b.id = k.branch_id
            WHERE k.is_active AND k.employee_id IS NULL AND b.tt_branch_id IS NOT NULL ORDER BY 1 LIMIT 30
            """, String.class);
        rep.put("unmatchedInMappedBranches", lost);
        return rep;
    }

    // ---------- ручная привязка человека из kln к сотруднику Таймтрекера

    /** Поиск по имени среди сотрудников kln. Телефон показываем только последними 4 цифрами. */
    public List<Map<String, Object>> searchUsers(String q) {
        String needle = q == null ? "" : q.trim();
        if (needle.length() < 2) return List.of();
        return jdbc.queryForList("""
            SELECT k.id, k.full_name AS "name", coalesce(b.title, '') AS "branch", k.is_active AS "active",
                   coalesce(right(k.phone_key, 4), '') AS "phoneTail",
                   array_to_string(k.possibles, ', ') AS "positions",
                   k.employee_id AS "linkedTo"
            FROM kln_user k LEFT JOIN kln_branch b ON b.id = k.branch_id
            WHERE k.full_name ILIKE :q ORDER BY k.is_active DESC, k.full_name LIMIT 30
            """, new MapSqlParameterSource("q", "%" + needle + "%"));
    }

    public Map<String, Object> link(long employeeId, long klnUserId, Long by) {
        Integer e = jdbc.queryForObject("SELECT count(*) FROM ext_employee WHERE id = :e", new MapSqlParameterSource("e", employeeId), Integer.class);
        Integer k = jdbc.queryForObject("SELECT count(*) FROM kln_user WHERE id = :k", new MapSqlParameterSource("k", klnUserId), Integer.class);
        if (e == null || e == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Нет такого сотрудника");
        if (k == null || k == 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Нет такого человека в kln");
        tx.executeWithoutResult(st -> {
            // один человек kln и один сотрудник — одна связь
            jdbc.update("DELETE FROM kln_link WHERE employee_id = :e OR kln_user_id = :k",
                new MapSqlParameterSource().addValue("e", employeeId).addValue("k", klnUserId));
            jdbc.update("INSERT INTO kln_link (kln_user_id, employee_id, created_by) VALUES (:k, :e, :by)",
                new MapSqlParameterSource().addValue("k", klnUserId).addValue("e", employeeId).addValue("by", by));
        });
        return rematch();
    }

    public Map<String, Object> unlink(long employeeId) {
        jdbc.update("DELETE FROM kln_link WHERE employee_id = :e", new MapSqlParameterSource("e", employeeId));
        return rematch();
    }

    // ---------- привязка ресторанов kln к филиалам Таймтрекера ----------

    public List<Map<String, Object>> branchMapping() {
        List<Map<String, Object>> kb = jdbc.getJdbcTemplate().queryForList("""
            SELECT b.id, b.title, b.tt_branch_id AS "ttBranchId",
                   (SELECT count(*) FROM kln_user u WHERE u.branch_id = b.id AND u.is_active) AS "users"
            FROM kln_branch b ORDER BY b.title
            """);
        List<Map<String, Object>> tt = jdbc.getJdbcTemplate().queryForList("SELECT id, title FROM ext_branch");
        for (Map<String, Object> row : kb) {
            if (row.get("ttBranchId") != null) continue;
            Set<String> a = words((String) row.get("title"));
            long best = -1;
            double bestScore = 0;
            for (Map<String, Object> t : tt) {
                Set<String> b = words((String) t.get("title"));
                Set<String> inter = new HashSet<>(a);
                inter.retainAll(b);
                Set<String> union = new HashSet<>(a);
                union.addAll(b);
                double sc = union.isEmpty() ? 0 : (double) inter.size() / union.size();
                if (sc > bestScore) {
                    bestScore = sc;
                    best = ((Number) t.get("id")).longValue();
                }
            }
            if (bestScore >= 0.3) row.put("suggestTtBranchId", best);
        }
        return kb;
    }

    private static Set<String> words(String s) {
        Set<String> r = new HashSet<>();
        if (s == null) return r;
        for (String w : s.toLowerCase().replaceAll("[^\\p{L}]+", " ").trim().split(" ")) {
            if (w.length() > 1 && !w.equals("fs") && !w.equals("im")) r.add(w);
        }
        return r;
    }

    public void mapBranch(long klnBranchId, Long ttBranchId) {
        List<Long> exist = new ArrayList<>();
        if (ttBranchId != null) {
            exist = jdbc.getJdbcTemplate().queryForList("SELECT id FROM ext_branch WHERE id = ?", Long.class, ttBranchId);
            if (exist.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Нет такого филиала Таймтрекера");
        }
        jdbc.update("UPDATE kln_branch SET tt_branch_id = :t WHERE id = :id",
            new MapSqlParameterSource().addValue("t", ttBranchId).addValue("id", klnBranchId));
    }
}