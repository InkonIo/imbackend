package com.imdemo.im.web;

import com.imdemo.im.domain.AuditEventType;
import com.imdemo.im.dto.Dto.AuditPage;
import com.imdemo.im.dto.Dto.ShiftReportDto;
import com.imdemo.im.dto.Dto.ShiftSummaryDto;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.AuditService;
import com.imdemo.im.service.ShiftReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;
    private final ShiftReportService reports;

    @GetMapping("/events")
    public AuditPage events(@AuthenticationPrincipal UserPrincipal p,
                            @RequestParam(required = false) Long userId,
                            @RequestParam(required = false) Long shiftId,
                            @RequestParam(required = false) Long outletId,
                            @RequestParam(required = false) AuditEventType type,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "50") int size) {
        return audit.search(p, userId, shiftId, outletId, type, from, to, page, size);
    }

    @GetMapping("/shifts")
    public List<ShiftSummaryDto> shifts(@AuthenticationPrincipal UserPrincipal p,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.list(p, date);
    }

    @GetMapping("/shifts/{id}")
    public ShiftReportDto shift(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return reports.report(p, id);
    }
}