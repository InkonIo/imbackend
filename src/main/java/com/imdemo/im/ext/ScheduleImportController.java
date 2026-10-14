package com.imdemo.im.ext;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.imdemo.im.security.UserPrincipal;

/** Загрузка Excel-графика менеджеров. Доступ: супер-админ, ответственная и директор — только по своему ресторану. */
@RestController
public class ScheduleImportController {

    private final ScheduleImportService svc;
    private final SchedAccess access;

    public ScheduleImportController(ScheduleImportService svc, SchedAccess access) {
        this.svc = svc;
        this.access = access;
    }

    @PostMapping("/api/sched/plan/branches/{b}/import-schedule/preview")
    public Map<String, Object> preview(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b, @RequestBody Map<String, String> body) {
        access.requireBranch(p, b);
        return svc.preview(b, body.get("data"));
    }

    @PostMapping("/api/sched/plan/branches/{b}/import-schedule/apply")
    public Map<String, Object> apply(@AuthenticationPrincipal UserPrincipal p, @PathVariable long b,
            @RequestBody ScheduleImportService.ApplyIn in) {
        access.requireBranch(p, b);
        return svc.apply(p, b, in);
    }
}