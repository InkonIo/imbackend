package com.imdemo.im.sheet.controller;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.sheet.dto.SheetDto.SaveRequest;
import com.imdemo.im.sheet.dto.SheetDto.Saved;
import com.imdemo.im.sheet.dto.SheetDto.Sheet;
import com.imdemo.im.sheet.service.SheetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sheets")
@RequiredArgsConstructor
public class SheetController {

    private final SheetService service;

    @GetMapping("/current")
    public Sheet current(@AuthenticationPrincipal UserPrincipal p) {
        return service.current(p);
    }

    @GetMapping("/{id}")
    public Sheet get(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.get(p, id);
    }

    @PutMapping("/{id}/values")
    public Saved save(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                      @Valid @RequestBody SaveRequest r) {
        return service.save(p, id, r.values());
    }
}