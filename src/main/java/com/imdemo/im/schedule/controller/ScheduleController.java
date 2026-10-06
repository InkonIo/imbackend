package com.imdemo.im.schedule.controller;

import com.imdemo.im.schedule.dto.ScheduleDto.*;
import com.imdemo.im.schedule.service.ScheduleService;
import com.imdemo.im.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService service;

    @GetMapping
    public Board board(@AuthenticationPrincipal UserPrincipal p, @RequestParam Long outletId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.board(p, outletId, from, to);
    }

    @PostMapping("/generate")
    public Board generate(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody GenerateRequest r) {
        return service.generate(p, r);
    }

    @PutMapping("/slot")
    public Result setSlot(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody SlotRequest r) {
        return service.setSlot(p, r);
    }

    @PostMapping("/swap")
    public Result swap(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody SwapRequest r) {
        return service.swap(p, r);
    }

    @PostMapping("/publish")
    public Map<String, Integer> publish(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody PublishRequest r) {
        return service.publish(p, r);
    }

    @PutMapping("/staff/{userId}")
    public Staff updateStaff(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long userId,
                             @Valid @RequestBody StaffRequest r) {
        return service.updateStaff(p, userId, r);
    }

    @PostMapping("/absences")
    public Absence addAbsence(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody AbsenceRequest r) {
        return service.addAbsence(p, r);
    }

    @DeleteMapping("/absences/{id}")
    public ResponseEntity<Void> deleteAbsence(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        service.deleteAbsence(p, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/my")
    public List<MySlot> my(@AuthenticationPrincipal UserPrincipal p,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.my(p, from, to);
    }

    @GetMapping("/today")
    public List<MySlot> today(@AuthenticationPrincipal UserPrincipal p) {
        return service.today(p);
    }
}