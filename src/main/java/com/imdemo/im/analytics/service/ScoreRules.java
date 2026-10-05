package com.imdemo.im.analytics.service;

import com.imdemo.im.analytics.domain.ShiftMetrics;

/** Формула баллов за смену. Все числа здесь: поменял, затем POST /api/admin/analytics/rebuild. */
public final class ScoreRules {
    private ScoreRules() {}

    public static final int PER_COMPLETED = 1;
    public static final int PER_ON_TIME = 1;
    public static final int PER_APPROVED = 2;
    public static final int PERFECT_BONUS = 10;
    public static final int PER_SKIPPED = -1;
    public static final int PER_NOT_DONE = -2;
    public static final int PER_CONFIRMED_MEDIUM = -3;
    public static final int PER_CONFIRMED_HIGH = -5;

    public static boolean isPerfect(ShiftMetrics m) {
        return m.getFinishedAt() != null
                && m.getItemsTotal() > 0
                && m.getItemsSkipped() == 0
                && m.getItemsNotDone() == 0
                && m.getItemsDone() + m.getItemsProblem() == m.getItemsTotal()
                && m.getConfirmedHigh() + m.getConfirmedMedium() == 0;
    }

    public static int score(ShiftMetrics m) {
        int s = (m.getItemsDone() + m.getItemsProblem()) * PER_COMPLETED
                + m.getDueOnTime() * PER_ON_TIME
                + m.getItemsApproved() * PER_APPROVED
                + m.getItemsSkipped() * PER_SKIPPED
                + m.getItemsNotDone() * PER_NOT_DONE
                + m.getConfirmedMedium() * PER_CONFIRMED_MEDIUM
                + m.getConfirmedHigh() * PER_CONFIRMED_HIGH;
        if (isPerfect(m)) s += PERFECT_BONUS;
        return s;
    }
}