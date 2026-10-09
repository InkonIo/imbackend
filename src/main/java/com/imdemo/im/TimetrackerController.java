package com.imdemo.im;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ext")
public class TimetrackerController {

    private final TimetrackerService service;

    public TimetrackerController(TimetrackerService service) {
        this.service = service;
    }

    @GetMapping("/employees")
    public Map<String, Object> employees(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean fired) {
        return service.list(q, branchId, fired);
    }

    @GetMapping("/schedule")
    public Map<String, Object> schedule(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long branchId) {
        return service.schedule(month, branchId);
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long branchId) {
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
