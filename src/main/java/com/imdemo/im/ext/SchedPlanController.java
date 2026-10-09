package com.imdemo.im.ext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.imdemo.im.security.UserPrincipal;

/**
 * Автосборка графика. Путь /api/sched/** закрыт от сотрудников в SecurityConfig,
 * а право «ответственная за расписание или супер-админ» проверяется в каждом методе.
 */
@RestController
public class SchedPlanController {

    private final SchedPlanService plan;
    private final KlnSyncService kln;
    private final SchedAccess access;

    public SchedPlanController(SchedPlanService plan, KlnSyncService kln, SchedAccess access) {
        this.plan = plan;
        this.kln = kln;
        this.access = access;
    }

    // ---------- справочники и настройки

    @GetMapping("/api/sched/plan/branches")
    public List<Map<String, Object>> branches(@AuthenticationPrincipal UserPrincipal p) {
        access.requireManage(p);
        return plan.branches();
    }

    @GetMapping("/api/sched/plan/positions")
    public List<Map<String, Object>> positions(@AuthenticationPrincipal UserPrincipal p) {
        access.requireManage(p);
        return plan.positions();
    }

    @GetMapping("/api/sched/plan/settings")
    public Map<String, Object> settings(@AuthenticationPrincipal UserPrincipal p) {
        access.requireManage(p);
        return plan.settings();
    }

    public record SettingsIn(Integer maxConsecutive, Integer minRestHours) {}

    @PutMapping("/api/sched/plan/settings")
    public Map<String, Object> saveSettings(@AuthenticationPrincipal UserPrincipal p, @RequestBody SettingsIn s) {
        access.requireManage(p);
        plan.saveSettings(p, s.maxConsecutive(), s.minRestHours());
        return plan.settings();
    }

    // ---------- нормы по ресторану

    @GetMapping("/api/sched/plan/branches/{b}/slots")
    public List<Map<String, Object>> slots(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b) {
        access.requireManage(p);
        return plan.slots(b);
    }

    @PutMapping("/api/sched/plan/branches/{b}/slots")
    public List<Map<String, Object>> saveSlots(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b,
            @RequestBody List<SchedPlanService.SlotIn> list) {
        access.requireManage(p);
        plan.saveSlots(b, list);
        return plan.slots(b);
    }

    @PostMapping("/api/sched/plan/branches/{b}/slots/preset")
    public List<Map<String, Object>> preset(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b,
            @RequestParam(defaultValue = "evening") String name) {
        access.requireManage(p);
        plan.preset(b, name);
        return plan.slots(b);
    }

    // ---------- сотрудники и позиции

    @GetMapping("/api/sched/plan/branches/{b}/employees")
    public List<Map<String, Object>> employees(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b) {
        access.requireManage(p);
        return plan.employees(b);
    }

    @PostMapping("/api/sched/plan/employees/{e}/positions/{code}")
    public Map<String, Object> addPosition(@AuthenticationPrincipal UserPrincipal p, @PathVariable long e, @PathVariable String code) {
        access.requireManage(p);
        plan.addPosition(e, code);
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/sched/plan/employees/{e}/positions/{code}")
    public Map<String, Object> removePosition(@AuthenticationPrincipal UserPrincipal p, @PathVariable long e, @PathVariable String code) {
        access.requireManage(p);
        return Map.of("ok", plan.removePosition(e, code) > 0);
    }

    // ---------- обучение

    @GetMapping("/api/sched/plan/branches/{b}/trainings")
    public List<Map<String, Object>> trainings(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b) {
        access.requireManage(p);
        return plan.trainings(b);
    }

    @PostMapping("/api/sched/plan/branches/{b}/trainings")
    public Map<String, Object> addTraining(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b,
            @RequestBody SchedPlanService.TrainingIn t) {
        access.requireManage(p);
        return Map.of("id", plan.addTraining(p, b, t));
    }

    @DeleteMapping("/api/sched/plan/branches/{b}/trainings/{id}")
    public Map<String, Object> deleteTraining(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b, @PathVariable long id) {
        access.requireManage(p);
        return Map.of("ok", plan.deleteTraining(b, id) > 0);
    }

    // ---------- черновики

    @PostMapping("/api/sched/plan/branches/{b}/generate")
    public Map<String, Object> generate(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        access.requireManage(p);
        return plan.generate(p, b, weekStart);
    }

    @GetMapping("/api/sched/plan/branches/{b}/drafts")
    public List<Map<String, Object>> drafts(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b) {
        access.requireManage(p);
        return plan.drafts(b);
    }

    @GetMapping("/api/sched/plan/drafts/{id}")
    public Map<String, Object> draft(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id) {
        access.requireManage(p);
        return plan.draft(id);
    }

    public record AssignIn(Long employeeId) {}

    @PutMapping("/api/sched/plan/drafts/{id}/shifts/{sid}")
    public Map<String, Object> assign(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id, @PathVariable long sid,
            @RequestBody AssignIn a) {
        access.requireManage(p);
        return plan.assign(id, sid, a.employeeId());
    }

    @PostMapping("/api/sched/plan/drafts/{id}/shifts")
    public Map<String, Object> addShift(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id,
            @RequestBody SchedPlanService.ShiftIn s) {
        access.requireManage(p);
        return plan.addShift(id, s);
    }

    @DeleteMapping("/api/sched/plan/drafts/{id}/shifts/{sid}")
    public Map<String, Object> deleteShift(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id, @PathVariable long sid) {
        access.requireManage(p);
        return Map.of("ok", plan.deleteShift(id, sid) > 0);
    }

    @PostMapping("/api/sched/plan/drafts/{id}/publish")
    public Map<String, Object> publish(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id,
            @RequestParam(defaultValue = "false") boolean confirmEmpty) {
        access.requireManage(p);
        return plan.publish(p, id, confirmEmpty);
    }

    // ---------- kln

    /** Долгий запрос (десятки обращений к kln): занимает до минуты. */
    @PostMapping("/api/sched/plan/kln/sync")
    public Map<String, Object> klnSync(@AuthenticationPrincipal UserPrincipal p) {
        access.requireManage(p);
        return kln.sync();
    }

    @PostMapping("/api/sched/plan/kln/rematch")
    public Map<String, Object> klnRematch(@AuthenticationPrincipal UserPrincipal p) {
        access.requireManage(p);
        return kln.rematch();
    }

    @GetMapping("/api/sched/plan/kln/branches")
    public List<Map<String, Object>> klnBranches(@AuthenticationPrincipal UserPrincipal p) {
        access.requireManage(p);
        return kln.branchMapping();
    }

    @GetMapping("/api/sched/plan/kln/users")
    public List<Map<String, Object>> klnUsers(@AuthenticationPrincipal UserPrincipal p, @RequestParam String q) {
        access.requireManage(p);
        return kln.searchUsers(q);
    }

    public record LinkIn(long klnUserId) {}

    @PutMapping("/api/sched/plan/employees/{e}/kln-link")
    public Map<String, Object> klnLink(@AuthenticationPrincipal UserPrincipal p, @PathVariable long e, @RequestBody LinkIn l) {
        access.requireManage(p);
        return kln.link(e, l.klnUserId(), p.id());
    }

    @DeleteMapping("/api/sched/plan/employees/{e}/kln-link")
    public Map<String, Object> klnUnlink(@AuthenticationPrincipal UserPrincipal p, @PathVariable long e) {
        access.requireManage(p);
        return kln.unlink(e);
    }

    public record MapIn(Long ttBranchId) {}

    @PutMapping("/api/sched/plan/kln/branches/{id}/map")
    public Map<String, Object> klnMap(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id, @RequestBody MapIn m) {
        access.requireManage(p);
        kln.mapBranch(id, m.ttBranchId());
        return Map.of("ok", true);
    }
}