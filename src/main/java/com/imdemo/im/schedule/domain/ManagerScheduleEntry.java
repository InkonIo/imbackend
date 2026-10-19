package com.imdemo.im.schedule.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "manager_schedule")
@Getter @Setter @NoArgsConstructor
public class ManagerScheduleEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_id", nullable = false)
    private Long weekId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sched_date", nullable = false)
    private LocalDate schedDate;

    @Column(name = "shift_start")
    private LocalTime shiftStart;

    @Column(name = "shift_end")
    private LocalTime shiftEnd;

    @Column(name = "day_off", nullable = false)
    private boolean dayOff = false;

    @Column(name = "comment")
    private String comment;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}