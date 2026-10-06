package com.imdemo.im.inventory.controller;

import com.imdemo.im.inventory.dto.InventoryDto.*;
import com.imdemo.im.inventory.service.InventoryService;
import com.imdemo.im.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService service;

    @GetMapping("/current")
    public ResponseEntity<Count> current(@AuthenticationPrincipal UserPrincipal p) {
        return service.current(p).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/start")
    public Count start(@AuthenticationPrincipal UserPrincipal p, @RequestBody(required = false) StartRequest r) {
        return service.start(p, r);
    }

    @GetMapping
    public List<Summary> list(@AuthenticationPrincipal UserPrincipal p,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(p, from, to);
    }

    @GetMapping("/{id}")
    public Count get(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.get(p, id);
    }

    @PutMapping("/{id}/lines/{lineId}")
    public Line updateLine(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                           @PathVariable Long lineId, @Valid @RequestBody LineRequest r) {
        return service.updateLine(p, id, lineId, r);
    }

    @PostMapping("/{id}/submit")
    public Count submit(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.submit(p, id);
    }
}