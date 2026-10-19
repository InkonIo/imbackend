package com.imdemo.im.workflow;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/** Отметки Workflow директора. Только директор и супер-админ, каждый видит и меняет свои отметки. */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private static final Pattern ITEM = Pattern.compile("^[a-z0-9._-]{1,64}$");
    private static final Pattern PERIOD = Pattern.compile("^[dwmqhy]:[0-9A-Za-z-]{1,20}$");

    private final NamedParameterJdbcTemplate jdbc;

    public WorkflowController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static void require(UserPrincipal p) {
        if (p == null || (p.role() != AccountRole.SUPER_ADMIN && p.role() != AccountRole.DIRECTOR)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Раздел доступен директору и супер-админу");
        }
    }

    @GetMapping("/done")
    public List<Map<String, Object>> done(@AuthenticationPrincipal UserPrincipal p, @RequestParam List<String> keys) {
        require(p);
        if (keys.isEmpty() || keys.size() > 20) throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректные периоды");
        for (String k : keys) if (!PERIOD.matcher(k).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный период");
        return jdbc.queryForList(
            "SELECT item_id AS \"itemId\", period_key AS \"periodKey\" FROM wf_done WHERE user_id = :u AND period_key IN (:k)",
            new MapSqlParameterSource().addValue("u", p.id()).addValue("k", keys));
    }

    public record ToggleIn(String itemId, String periodKey, boolean done) {}

    @PostMapping("/toggle")
    public Map<String, Object> toggle(@AuthenticationPrincipal UserPrincipal p, @RequestBody ToggleIn in) {
        require(p);
        if (in.itemId() == null || !ITEM.matcher(in.itemId()).matches()
            || in.periodKey() == null || !PERIOD.matcher(in.periodKey()).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректные данные");
        }
        MapSqlParameterSource ps = new MapSqlParameterSource().addValue("u", p.id()).addValue("i", in.itemId()).addValue("k", in.periodKey());
        if (in.done()) {
            jdbc.update("INSERT INTO wf_done (user_id, item_id, period_key) VALUES (:u, :i, :k) ON CONFLICT DO NOTHING", ps);
        } else {
            jdbc.update("DELETE FROM wf_done WHERE user_id = :u AND item_id = :i AND period_key = :k", ps);
        }
        return Map.of("ok", true);
    }
}