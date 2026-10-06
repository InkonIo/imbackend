package com.imdemo.im.schedule.domain;

import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.ShiftRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "schedule_slot")
@Getter
@Setter
@NoArgsConstructor
public class ScheduleSlot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_part", nullable = false)
    private DayPart dayPart;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_role", nullable = false)
    private ShiftRole role;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}