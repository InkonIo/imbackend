package com.imdemo.im.ext;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/**
 * Кто может управлять расписанием сотрудников.
 * Право есть у: SUPER_ADMIN и у одного пользователя, которого выбрал супер-админ (ext_setting.schedule_owner_user_id).
 * Роль EMPLOYEE этого права не получает никогда.
 */
@Component
public class SchedAccess {

    public static final String OWNER_KEY = "schedule_owner_user_id";

    private final NamedParameterJdbcTemplate jdbc;

    public SchedAccess(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String setting(String key) {
        List<String> r = jdbc.queryForList("SELECT value FROM ext_setting WHERE key = :k", Map.of("k", key), String.class);
        return r.isEmpty() ? null : r.get(0);
    }

    public int intSetting(String key, int def) {
        try {
            String v = setting(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void putSetting(String key, String value, Long by) {
        jdbc.update("""
            INSERT INTO ext_setting (key, value, updated_at, updated_by) VALUES (:k, :v, now(), :by)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by
            """, new MapSqlParameterSource().addValue("k", key).addValue("v", value).addValue("by", by));
    }

    public Long ownerId() {
        try {
            String v = setting(OWNER_KEY);
            return v == null || v.isBlank() ? null : Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean canManage(UserPrincipal p) {
        if (p == null || p.role() == AccountRole.EMPLOYEE) return false;
        if (p.role() == AccountRole.SUPER_ADMIN) return true;
        Long owner = ownerId();
        return owner != null && owner.equals(p.id());
    }

    public void requireManage(UserPrincipal p) {
        if (!canManage(p)) throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к управлению расписанием");
    }

    // ---------- менеджеры авторизации для SecurityConfig ----------

    private static UserPrincipal principal(Authentication a) {
        return a != null && a.getPrincipal() instanceof UserPrincipal p ? p : null;
    }

    private static boolean isEmployee(Authentication a) {
        return a.getAuthorities().stream().anyMatch(g -> "ROLE_EMPLOYEE".equals(g.getAuthority()));
    }

    /** Любой вошедший, КРОМЕ сотрудника. Заменяет authenticated() на всех не-сотрудничьих путях. */
    public AuthorizationManager<RequestAuthorizationContext> notEmployee() {
        return (auth, ctx) -> {
            Authentication a = auth.get();
            boolean ok = a != null && a.isAuthenticated() && principal(a) != null && !isEmployee(a);
            return new AuthorizationDecision(ok);
        };
    }

    /** Супер-админ, директор или назначенная ответственная. */
    public AuthorizationManager<RequestAuthorizationContext> adminDirectorOrOwner() {
        return (auth, ctx) -> {
            Authentication a = auth.get();
            UserPrincipal p = a == null ? null : principal(a);
            if (p == null) return new AuthorizationDecision(false);
            boolean ok = p.role() == AccountRole.SUPER_ADMIN || p.role() == AccountRole.DIRECTOR || canManage(p);
            return new AuthorizationDecision(ok);
        };
    }
}
