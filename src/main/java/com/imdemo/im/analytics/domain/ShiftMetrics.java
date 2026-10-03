package com.imdemo.im.analytics.domain;

import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.ShiftRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "shift_metrics")
@Getter
@Setter
@NoArgsConstructor
public class ShiftMetrics {
    @Id
    @Column(name = "shift_id")
    private Long shiftId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_part", nullable = false)
    private DayPart dayPart;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_role", nullable = false)
    private ShiftRole shiftRole;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(name = "active_min", nullable = false)
    private int activeMin;

    @Column(name = "items_total", nullable = false)
    private int itemsTotal;

    @Column(name = "items_done", nullable = false)
    private int itemsDone;

    @Column(name = "items_problem", nullable = false)
    private int itemsProblem;

    @Column(name = "items_skipped", nullable = false)
    private int itemsSkipped;

    @Column(name = "items_not_done", nullable = false)
    private int itemsNotDone;

    @Column(name = "completion_pct", nullable = false)
    private int completionPct;

    @Column(name = "due_total", nullable = false)
    private int dueTotal;

    @Column(name = "due_on_time", nullable = false)
    private int dueOnTime;

    @Column(name = "on_time_pct")
    private Integer onTimePct;

    @Column(name = "photos_total", nullable = false)
    private int photosTotal;

    @Column(name = "comments_total", nullable = false)
    private int commentsTotal;

    @Column(name = "flags_total", nullable = false)
    private int flagsTotal;

    @Column(name = "flags_high", nullable = false)
    private int flagsHigh;

    @Column(name = "flags_open", nullable = false)
    private int flagsOpen;

    @Column(name = "flags_confirmed", nullable = false)
    private int flagsConfirmed;

    @Column(name = "flags_dismissed", nullable = false)
    private int flagsDismissed;

    @Column(name = "too_fast", nullable = false)
    private int tooFast;

    @Column(nullable = false)
    private int late;

    @Column(name = "items_approved", nullable = false)
    private int itemsApproved;

    @Column(name = "items_rejected", nullable = false)
    private int itemsRejected;

    /** Баллы: формула будет на следующем шаге. */
    private Integer score;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt = OffsetDateTime.now();
}