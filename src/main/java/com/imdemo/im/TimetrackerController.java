package com.imdemo.im;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imdemo.im.ext.SchedAccess;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;

/**
 * Данные тайм-трекера. Директор видит только свои рестораны (sched_branch_access),
 * супер-админ и ответственная за расписание, все.
 * Если у тебя импорт TimetrackerService отличается (например com.imdemo.im.TimetrackerService), оставь свой import.
 */
@RestController
@RequestMapping("/api/ext")
public class TimetrackerController {

    private final TimetrackerService service;
    private final SchedAccess access;

    public TimetrackerController(TimetrackerService service, SchedAccess access) {
        this.service = service;
        this.access = access;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : -1L;
    }

    /** Для ограниченного пользователя ресторан должен быть указан и разрешён. */
    private void checkBranch(UserPrincipal p, Long branchId) {
        List<Long> ids = access.branchIds(p);
        if (ids == null) return;
        if (branchId == null || !ids.contains(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этому ресторану");
        }
    }

    @GetMapping("/employees")
    @SuppressWarnings("unchecked")
    public Map<String, Object> employees(@AuthenticationPrincipal UserPrincipal p,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean fired) {
        List<Long> ids = access.branchIds(p);
        if (ids != null && branchId != null && !ids.contains(branchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этому ресторану");
        }
        Map<String, Object> r = service.list(q, branchId, fired);
        if (ids == null) return r;
        Map<String, Object> out = new LinkedHashMap<>(r);
        List<Map<String, Object>> emps = new ArrayList<>();
        for (Map<String, Object> e : (List<Map<String, Object>>) r.get("employees")) {
            if (ids.contains(num(e.get("branchId")))) emps.add(e);
        }
        List<Map<String, Object>> brs = new ArrayList<>();
        for (Map<String, Object> b : (List<Map<String, Object>>) r.get("branches")) {
            if (ids.contains(num(b.get("id")))) brs.add(b);
        }
        out.put("employees", emps);
        out.put("branches", brs);
        return out;
    }

    @GetMapping("/schedule")
    public Map<String, Object> schedule(@AuthenticationPrincipal UserPrincipal p,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long branchId) {
        checkBranch(p, branchId);
        return service.schedule(month, branchId);
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@AuthenticationPrincipal UserPrincipal p,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long branchId) {
        checkBranch(p, branchId);
        return service.analytics(month, branchId);
    }

    @PostMapping("/sync-schedule")
    public Map<String, Object> syncSchedule(@RequestParam(required = false) String month) {
        return service.syncSchedule(month);
    }

    @PostMapping("/sync")
    public Map<String, Object> sync() {
        return service.syncEmployees();
    }
}