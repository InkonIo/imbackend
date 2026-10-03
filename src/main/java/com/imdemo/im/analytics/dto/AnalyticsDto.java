package com.imdemo.im.analytics.dto;

public final class AnalyticsDto {
    private AnalyticsDto() {}

    public record UserStats(Long userId, String userName, String userLogin,
                            long shifts, long itemsTotal, Integer completionPct, Integer onTimePct,
                            long skipped, long notDone, long photos, long comments,
                            long flags, long flagsConfirmed, long tooFast, long late, long rejected,
                            long activeMin) {}
}