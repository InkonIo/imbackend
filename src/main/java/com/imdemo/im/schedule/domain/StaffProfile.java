package com.imdemo.im.schedule.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "staff_profile")
@Getter
@Setter
@NoArgsConstructor
public class StaffProfile {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_title", nullable = false)
    private JobTitle jobTitle = JobTitle.MANAGER;

    @Column(name = "can_inside", nullable = false)
    private boolean canInside = true;

    @Column(nullable = false)
    private boolean schedulable = true;

    @Column(name = "max_shifts_week", nullable = false)
    private int maxShiftsWeek = 5;
}