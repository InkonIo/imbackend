package com.imdemo.im.sheet.service;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.Outlet;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Справочник приоритетов дня. Создают и правят супер-админ (все) и директор (только свои точки). */
@Service
@RequiredArgsConstructor
public class PriorityService {

    public record In(String title, String category, Long outletId, Boolean active, Integer sortOrder) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "Нет доступа");
    }

    private static ApiException bad(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, m);
    }

    private void requireAdmin(UserPrincipal p) {
        if (p == null || (p.role() != AccountRole.SUPER_ADMIN && p.role() != AccountRole.DIRECTOR)) throw forbidden();
    }

    private Set<Long> ownOutlets(UserPrincipal p) {
        return users.findById(p.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /** Можно ли менять запись, привязанную к точке outletId (null = для всех точек). */
    private boolean canTouch(UserPrincipal p, Long outletId) {
        if (p.role() == AccountRole.SUPER_ADMIN) return true;
        return outletId != null && ownOutlets(p).contains(outletId);
    }

    /** Активные шаблоны для бланка точки: общие + этой точки. Видят все, кроме сотрудников (режет SecurityConfig). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> available(Long outletId) {
        return jdbc.queryForList("""
            SELECT id, title, category FROM sheet_priority
            WHERE active AND (outlet_id IS NULL OR outlet_id = :o)
            ORDER BY sort_order, category NULLS LAST, title
            """, new MapSqlParameterSource("o", outletId));
    }

    /** Список для управления. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> manage(UserPrincipal p) {
        requireAdmin(p);
        String where = "";
        MapSqlParameterSource ps = new MapSqlParameterSource();
        if (p.role() == AccountRole.DIRECTOR) {
            Set<Long> own = ownOutlets(p);
            if (own.isEmpty()) return List.of();
            where = "WHERE pr.outlet_id IS NULL OR pr.outlet_id IN (:own)";
            ps.addValue("own", own);
        }
        return jdbc.queryForList("""
            SELECT pr.id, pr.title, pr.category, pr.outlet_id AS "outletId", o.name AS "outletName",
                   pr.active, pr.sort_order AS "sortOrder", (pr.outlet_id IS NULL) AS "global"
            FROM sheet_priority pr LEFT JOIN outlet o ON o.id = pr.outlet_id
            """ + where + " ORDER BY pr.sort_order, pr.id", ps);
    }

    private static String clean(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    @Transactional
    public Map<String, Object> create(UserPrincipal p, In in) {
        requireAdmin(p);
        String title = clean(in.title(), 200);
        if (title.isEmpty()) throw bad("Напиши название приоритета");
        if (!canTouch(p, in.outletId())) {
            throw p.role() == AccountRole.DIRECTOR ? bad("Выбери свою точку") : forbidden();
        }
        Long id = jdbc.queryForObject("""
            INSERT INTO sheet_priority (title, category, outlet_id, active, sort_order, created_by)
            VALUES (:t, :c, :o, :a, :s, :u) RETURNING id
            """, new MapSqlParameterSource().addValue("t", title)
                .addValue("c", clean(in.category(), 40).isEmpty() ? null : clean(in.category(), 40))
                .addValue("o", in.outletId()).addValue("a", in.active() == null || in.active())
                .addValue("s", in.sortOrder() == null ? 0 : in.sortOrder()).addValue("u", p.id()), Long.class);
        return Map.of("id", id);
    }

    private Long outletOf(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT outlet_id FROM sheet_priority WHERE id = :i",
                new MapSqlParameterSource("i", id));
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Приоритет не найден");
        Object o = r.get(0).get("outlet_id");
        return o == null ? null : ((Number) o).longValue();
    }

    @Transactional
    public Map<String, Object> update(UserPrincipal p, Long id, In in) {
        requireAdmin(p);
        if (!canTouch(p, outletOf(id))) throw forbidden();
        String title = clean(in.title(), 200);
        if (title.isEmpty()) throw bad("Напиши название приоритета");
        if (!canTouch(p, in.outletId())) throw forbidden();
        jdbc.update("""
            UPDATE sheet_priority SET title = :t, category = :c, outlet_id = :o, active = :a,
                   sort_order = :s, updated_at = now() WHERE id = :i
            """, new MapSqlParameterSource().addValue("t", title)
                .addValue("c", clean(in.category(), 40).isEmpty() ? null : clean(in.category(), 40))
                .addValue("o", in.outletId()).addValue("a", in.active() == null || in.active())
                .addValue("s", in.sortOrder() == null ? 0 : in.sortOrder()).addValue("i", id));
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> delete(UserPrincipal p, Long id) {
        requireAdmin(p);
        if (!canTouch(p, outletOf(id))) throw forbidden();
        jdbc.update("DELETE FROM sheet_priority WHERE id = :i", new MapSqlParameterSource("i", id));
        return Map.of("ok", true);
    }
}