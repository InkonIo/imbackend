package com.imdemo.im.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "shift_session")
@Getter
@Setter
@NoArgsConstructor
public class ShiftSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id")
    private Outlet outlet;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_role", nullable = false)
    private ShiftRole shiftRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_part", nullable = false)
    private DayPart dayPart;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}