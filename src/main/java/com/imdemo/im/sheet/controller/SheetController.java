package com.imdemo.im.sheet.controller;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.sheet.dto.SheetDto.SaveRequest;
import com.imdemo.im.sheet.dto.SheetDto.Saved;
import com.imdemo.im.sheet.dto.SheetDto.Sheet;
import com.imdemo.im.sheet.service.SheetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/sheets")
@RequiredArgsConstructor
public class SheetController {

    private final SheetService service;

    @GetMapping("/current")
    public Sheet current(@AuthenticationPrincipal UserPrincipal p) {
        return service.current(p);
    }

    /** Архив бланков за период (супер-админ: все точки, директор: свои). */
    @GetMapping
    public List<SheetService.ListRow> list(@AuthenticationPrincipal UserPrincipal p,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(required = false) Long outletId) {
        return service.list(p, from, to, outletId);
    }

    @GetMapping("/{id}")
    public Sheet get(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.get(p, id);
    }

    /** Результат за вчера и цели на сегодня из отчёта iiko (для автозаполнения бланка). */
    @GetMapping("/{id}/auto")
    public java.util.Map<String, Object> auto(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.auto(p, id);
    }

    /** Загрузка отчёта iiko из бланка: данные сразу попадают в базу. */
    @PostMapping("/{id}/sales-import")
    public java.util.Map<String, Object> salesImport(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                                     @RequestBody java.util.Map<String, String> body) {
        return service.importSales(p, id, body.get("fileName"), body.get("data"));
    }

    /** Excel «Прогноз продаж»: план по дням/часам и план людей. */
    @PostMapping("/{id}/forecast-import")
    public java.util.Map<String, Object> forecastImport(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                                        @RequestBody java.util.Map<String, String> body) {
        return service.importForecast(p, id, body.get("fileName"), body.get("data"));
    }

    /** Excel «10. Ежечасный сэндвич» за один день. */
    @PostMapping("/{id}/sandwich-import")
    public java.util.Map<String, Object> sandwichImport(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                                        @RequestBody java.util.Map<String, String> body) {
        return service.importSandwich(p, id, body.get("fileName"), body.get("data"));
    }

    @PutMapping("/{id}/values")
    public Saved save(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                      @Valid @RequestBody SaveRequest r) {
        return service.save(p, id, r.values());
    }
}