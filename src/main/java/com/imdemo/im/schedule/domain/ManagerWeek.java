package com.imdemo.im.schedule.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "manager_week")
@Getter @Setter @NoArgsConstructor
public class ManagerWeek {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "title")
    private String title;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}