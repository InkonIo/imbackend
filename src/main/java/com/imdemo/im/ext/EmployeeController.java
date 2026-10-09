package com.imdemo.im.ext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.imdemo.im.security.UserPrincipal;

/** Только для роли EMPLOYEE (см. SecurityConfig). Никаких employeeId в параметрах. */
@RestController
@RequestMapping("/api/employee")
public class EmployeeController {

    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal UserPrincipal p) {
        return service.me(p);
    }

    @GetMapping("/schedule")
    public Map<String, Object> schedule(@AuthenticationPrincipal UserPrincipal p, @RequestParam(required = false) String month) {
        return service.schedule(p, month);
    }

    @GetMapping("/coworkers")
    public List<Map<String, Object>> coworkers(@AuthenticationPrincipal UserPrincipal p, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return service.coworkers(p, day);
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@AuthenticationPrincipal UserPrincipal p) {
        return service.myRequests(p);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal UserPrincipal p, @RequestBody EmployeeService.NewRequest body) {
        return service.create(p, body);
    }

    @DeleteMapping("/requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal UserPrincipal p, @PathVariable long id) {
        service.cancel(p, id);
    }
}
