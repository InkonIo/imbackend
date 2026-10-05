package com.imdemo.im.analytics.dto;

import com.imdemo.im.domain.ShiftRole;

import java.time.LocalDate;
import java.util.List;

public final class RatingDto {
    private RatingDto() {}

    public record Entry(int rank, Long userId, String name, long shifts, long score, double avgScore,
                       int completionPct, Integer onTimePct, long violations, long photos, long perfect) {}

    public record Board(LocalDate from, LocalDate to, ShiftRole role, Long outletId, List<Entry> entries) {}
}