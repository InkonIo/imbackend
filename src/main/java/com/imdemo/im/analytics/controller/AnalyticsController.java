package com.imdemo.im.analytics.controller;

import com.imdemo.im.analytics.dto.AnalyticsDto.UserStats;
import com.imdemo.im.analytics.service.AnalyticsService;
import com.imdemo.im.analytics.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final MetricsService metrics;

    @GetMapping("/users")
    public List<UserStats> users(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.users(from, to);
    }

    @PostMapping("/rebuild")
    public Map<String, Integer> rebuild() {
        return Map.of("shifts", metrics.rebuildAll());
    }
}