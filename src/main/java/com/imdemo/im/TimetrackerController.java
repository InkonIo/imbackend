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

    @PostMapping("/sync")
    public Map<String, Object> sync() {
        return service.syncEmployees();
    }
}
