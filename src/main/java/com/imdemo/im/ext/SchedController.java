package com.imdemo.im.ext;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imdemo.im.security.UserPrincipal;

/**
 * Для ответственной за расписание и супер-админа. Любой метод сам проверяет право через SchedAccess —
 * путь в SecurityConfig закрыт только от EMPLOYEE, а «ответственная» определяется по базе.
 */
@RestController
public class SchedController {

    private final SchedService service;
    private final SchedAccess access;

    public SchedController(SchedService service, SchedAccess access) {
        this.service = service;
        this.access = access;
    }

    /** Открыто всем вошедшим (и сотруднику): что мне можно показывать в интерфейсе. */
    @GetMapping("/api/me/caps")
    public Map<String, Object> caps(@AuthenticationPrincipal UserPrincipal p) {
        return service.caps(p);
    }

    @GetMapping("/api/sched/requests")
    public List<Map<String, Object>> requests(@AuthenticationPrincipal UserPrincipal p,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long branchId) {
        access.requireManage(p);
        return service.requests(status, month, branchId);
    }

    public record Decision(boolean approve, String note) {}

    @PostMapping("/api/sched/requests/{id}/decide")
    public Map<String, Object> decide(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id, @RequestBody Decision d) {
        access.requireManage(p);
        service.decide(p, id, d.approve(), d.note());
        return Map.of("ok", true);
    }

    @GetMapping("/api/sched/accounts")
    public List<Map<String, Object>> accounts(@AuthenticationPrincipal UserPrincipal p,
            @RequestParam(required = false) String q, @RequestParam(required = false) Long branchId) {
        access.requireManage(p);
        return service.accounts(q, branchId);
    }

    public record CreateAccounts(List<Long> employeeIds) {}

    @PostMapping("/api/sched/accounts")
    public List<Map<String, Object>> create(@AuthenticationPrincipal UserPrincipal p, @RequestBody CreateAccounts body) {
        access.requireManage(p);
        return service.createAccounts(p, body.employeeIds());
    }

    @PostMapping("/api/sched/accounts/{employeeId}/reset")
    public Map<String, Object> reset(@AuthenticationPrincipal UserPrincipal p, @PathVariable long employeeId) {
        access.requireManage(p);
        return service.resetPassword(employeeId);
    }

    public record Active(boolean active) {}

    @PostMapping("/api/sched/accounts/{employeeId}/active")
    public Map<String, Object> active(@AuthenticationPrincipal UserPrincipal p, @PathVariable long employeeId, @RequestBody Active a) {
        access.requireManage(p);
        service.setActive(employeeId, a.active());
        return Map.of("ok", true);
    }

    // ----- выбор ответственной: /api/admin/** → только SUPER_ADMIN (правило уже есть в SecurityConfig) -----

    @GetMapping("/api/admin/sched/owner")
    public Map<String, Object> owner() {
        return service.owner();
    }

    public record Owner(Long userId, Integer leadDays) {}

    @PutMapping("/api/admin/sched/owner")
    public Map<String, Object> setOwner(@AuthenticationPrincipal UserPrincipal p, @RequestBody Owner o) {
        service.setOwner(p, o.userId(), o.leadDays());
        return service.owner();
    }
}
