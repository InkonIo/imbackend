package com.imdemo.im.board;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.Outlet;
import com.imdemo.im.repo.ShiftSessionRepository;
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

/**
 * Заметки точки. Видят и пишут: супер-админ (все точки), директор (свои точки),
 * менеджер/сотрудник (точка, на которой у него открыта смена). Править текст и удалять — автор,
 * директор своей точки и супер-админ; менять статус — любой, кто имеет доступ к точке.
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    public record In(Long outletId, String title, String body) {}

    public record StatusIn(String status, String comment) {}

    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "DONE");

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository users;
    private final ShiftSessionRepository shifts;

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой точке");
    }

    private static ApiException bad(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, m);
    }

    /** Точки, с которыми пользователь может работать. */
    private Set<Long> allowedOutlets(UserPrincipal p) {
        if (p.role() == AccountRole.SUPER_ADMIN) {
            return jdbc.getJdbcTemplate().queryForList("SELECT id FROM outlet", Long.class).stream().collect(Collectors.toSet());
        }
        if (p.role() == AccountRole.DIRECTOR) {
            return users.findById(p.id())
                    .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                    .orElse(Set.of());
        }
        return shifts.findFirstByUserIdAndFinishedAtIsNull(p.id())
                .map(s -> Set.of(s.getOutlet().getId()))
                .orElse(Set.of());
    }

    private boolean isBoss(UserPrincipal p, Long outletId) {
        if (p.role() == AccountRole.SUPER_ADMIN) return true;
        return p.role() == AccountRole.DIRECTOR && allowedOutlets(p).contains(outletId);
    }

    private void requireOutlet(UserPrincipal p, Long outletId) {
        if (outletId == null || !allowedOutlets(p).contains(outletId)) throw forbidden();
    }

    private Map<String, Object> noteRow(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT id, outlet_id AS \"outletId\", created_by AS \"createdBy\" FROM outlet_note WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Заметка не найдена");
        return r.get(0);
    }

    /** Список: незакрытые + закрытые за последние 7 дней (all=true — вообще все). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UserPrincipal p, Long outletId, boolean all) {
        Set<Long> ids;
        if (outletId != null) {
            requireOutlet(p, outletId);
            ids = Set.of(outletId);
        } else {
            ids = allowedOutlets(p);
        }
        if (ids.isEmpty()) return List.of();
        return jdbc.queryForList("""
            SELECT n.id, n.outlet_id AS "outletId", o.name AS "outletName", n.title, n.body, n.status,
                   to_char(n.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS "createdAt",
                   to_char(n.status_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS "statusAt",
                   n.created_by AS "createdBy", cu.full_name AS "createdByName",
                   ru.full_name AS "responsibleName", su.full_name AS "statusByName"
            FROM outlet_note n
              JOIN outlet o ON o.id = n.outlet_id
              LEFT JOIN app_user cu ON cu.id = n.created_by
              LEFT JOIN app_user ru ON ru.id = n.responsible_id
              LEFT JOIN app_user su ON su.id = n.status_by
            WHERE n.outlet_id IN (:ids)
              AND (:all OR n.status <> 'DONE' OR n.closed_at > now() - interval '7 days')
            ORDER BY (n.status = 'DONE'), n.created_at DESC
            LIMIT 300
            """, new MapSqlParameterSource().addValue("ids", ids).addValue("all", all));
    }

    @Transactional
    public Map<String, Object> create(UserPrincipal p, In in) {
        requireOutlet(p, in.outletId());
        String title = in.title() == null ? "" : in.title().trim();
        if (title.isEmpty()) throw bad("Напиши, что случилось");
        if (title.length() > 200) throw bad("Заголовок слишком длинный (до 200 символов)");
        String body = in.body() == null || in.body().isBlank() ? null : in.body().trim();
        Long id = jdbc.queryForObject("""
            INSERT INTO outlet_note (outlet_id, title, body, created_by)
            VALUES (:o, :t, :b, :u) RETURNING id
            """, new MapSqlParameterSource().addValue("o", in.outletId()).addValue("t", title)
                .addValue("b", body).addValue("u", p.id()), Long.class);
        return Map.of("id", id);
    }

    @Transactional
    public Map<String, Object> update(UserPrincipal p, Long id, In in) {
        Map<String, Object> n = noteRow(id);
        Long outletId = (Long) n.get("outletId");
        requireOutlet(p, outletId);
        if (!isBoss(p, outletId) && !p.id().equals(n.get("createdBy"))) throw forbidden();
        String title = in.title() == null ? "" : in.title().trim();
        if (title.isEmpty()) throw bad("Напиши, что случилось");
        jdbc.update("UPDATE outlet_note SET title = :t, body = :b, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("t", title)
                        .addValue("b", in.body() == null || in.body().isBlank() ? null : in.body().trim())
                        .addValue("id", id));
        return Map.of("ok", true);
    }

    /** Сменить статус. Кто нажал — тот и ответственный (взял в работу / закрыл). */
    @Transactional
    public Map<String, Object> setStatus(UserPrincipal p, Long id, StatusIn in) {
        Map<String, Object> n = noteRow(id);
        requireOutlet(p, (Long) n.get("outletId"));
        if (in.status() == null || !STATUSES.contains(in.status())) throw bad("Неизвестный статус");
        String comment = in.comment() == null || in.comment().isBlank() ? null : in.comment().trim();
        if (comment != null && comment.length() > 500) throw bad("Комментарий слишком длинный (до 500 символов)");
        boolean done = "DONE".equals(in.status());
        boolean reopened = "OPEN".equals(in.status());
        jdbc.update("""
            UPDATE outlet_note SET status = :s, status_by = :u, status_at = now(), updated_at = now(),
                   responsible_id = CASE WHEN :reopen THEN NULL ELSE :u END,
                   closed_at = CASE WHEN :done THEN now() ELSE NULL END
            WHERE id = :id
            """, new MapSqlParameterSource().addValue("s", in.status()).addValue("u", p.id())
                .addValue("reopen", reopened).addValue("done", done).addValue("id", id));
        jdbc.update("INSERT INTO outlet_note_log (note_id, user_id, status, comment) VALUES (:n, :u, :s, :c)",
                new MapSqlParameterSource().addValue("n", id).addValue("u", p.id())
                        .addValue("s", in.status()).addValue("c", comment));
        return Map.of("ok", true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> log(UserPrincipal p, Long id) {
        requireOutlet(p, (Long) noteRow(id).get("outletId"));
        return jdbc.queryForList("""
            SELECT l.status, l.comment, u.full_name AS "userName",
                   to_char(l.at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS "at"
            FROM outlet_note_log l LEFT JOIN app_user u ON u.id = l.user_id
            WHERE l.note_id = :id ORDER BY l.at
            """, new MapSqlParameterSource("id", id));
    }

    @Transactional
    public Map<String, Object> delete(UserPrincipal p, Long id) {
        Map<String, Object> n = noteRow(id);
        Long outletId = (Long) n.get("outletId");
        requireOutlet(p, outletId);
        if (!isBoss(p, outletId) && !p.id().equals(n.get("createdBy"))) throw forbidden();
        jdbc.update("DELETE FROM outlet_note WHERE id = :id", new MapSqlParameterSource("id", id));
        return Map.of("ok", true);
    }
}