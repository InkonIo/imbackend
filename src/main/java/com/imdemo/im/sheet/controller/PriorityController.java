package com.imdemo.im.sheet.controller;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.sheet.service.PriorityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sheet-priorities")
@RequiredArgsConstructor
public class PriorityController {

    private final PriorityService service;

    /** Шаблоны для бланка выбранной точки (менеджеры и инсайды). */
    @GetMapping
    public List<Map<String, Object>> available(@RequestParam Long outletId) {
        return service.available(outletId);
    }

    /** Все шаблоны для управления (супер-админ и директор). */
    @GetMapping("/manage")
    public List<Map<String, Object>> manage(@AuthenticationPrincipal UserPrincipal p) {
        return service.manage(p);
    }

    @PostMapping
    public Map<String, Object> create(@AuthenticationPrincipal UserPrincipal p, @RequestBody PriorityService.In in) {
        return service.create(p, in);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                      @RequestBody PriorityService.In in) {
        return service.update(p, id, in);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.delete(p, id);
    }
}