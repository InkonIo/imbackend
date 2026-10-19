package com.imdemo.im.schedule.controller;

import com.imdemo.im.schedule.dto.ScheduleDto.*;
import com.imdemo.im.schedule.service.ExcelScheduleImporter;
import com.imdemo.im.schedule.service.ManagerScheduleService;
import com.imdemo.im.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/manager-schedule")
@RequiredArgsConstructor
public class ManagerScheduleController {

    private final ManagerScheduleService service;
    private final ExcelScheduleImporter importer;

    @GetMapping("/weeks")
    public List<ManagerWeekDto> weeks(@AuthenticationPrincipal UserPrincipal p,
                                      @RequestParam Long outletId) {
        return service.weeks(p, outletId);
    }

    @GetMapping("/weeks/{weekId}")
    public ManagerBoardDto board(@AuthenticationPrincipal UserPrincipal p,
                                 @PathVariable Long weekId) {
        return service.board(p, weekId);
    }

    @PostMapping("/weeks")
    public ManagerWeekDto createWeek(@AuthenticationPrincipal UserPrincipal p,
                                     @Valid @RequestBody CreateWeekRequest r) {
        return service.createWeek(p, r);
    }

    @PutMapping("/entry")
    public ManagerEntryDto saveEntry(@AuthenticationPrincipal UserPrincipal p,
                                     @Valid @RequestBody SaveEntryRequest r) {
        return service.saveEntry(p, r);
    }

    @DeleteMapping("/weeks/{weekId}")
    public ResponseEntity<Void> deleteWeek(@AuthenticationPrincipal UserPrincipal p,
                                            @PathVariable Long weekId) {
        service.deleteWeek(p, weekId);
        return ResponseEntity.noContent().build();
    }

    /** Получить список листов из Excel файла */
    @PostMapping("/import/sheets")
    public ResponseEntity<List<String>> getSheets(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(importer.sheetNames(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Импортировать лист Excel в расписание */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importExcel(
            @AuthenticationPrincipal UserPrincipal p,
            @RequestParam Long outletId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String sheetName) {
        try {
            var result = importer.importSheet(outletId, file, sheetName);
            return ResponseEntity.ok(Map.of(
                "log", result.log,
                "error", result.error != null ? result.error : ""
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "log", List.of()));
        }
    }
}
