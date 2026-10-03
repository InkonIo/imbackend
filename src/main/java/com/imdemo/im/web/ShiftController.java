package com.imdemo.im.web;

import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.ShiftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    @PostMapping("/start")
    public ShiftDto start(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody StartShiftRequest r) {
        return shiftService.start(p.id(), r);
    }

    @GetMapping("/current")
    public ResponseEntity<ShiftDto> current(@AuthenticationPrincipal UserPrincipal p) {
        return shiftService.current(p.id()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/finish")
    public ShiftDto finish(@AuthenticationPrincipal UserPrincipal p) {
        return shiftService.finish(p.id());
    }
}