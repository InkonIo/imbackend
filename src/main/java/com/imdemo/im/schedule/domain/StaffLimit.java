package com.imdemo.im.schedule.domain;

import com.imdemo.im.domain.DayPart;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** «Когда я не могу»: дни недели + часть дня (или весь день) + срок (или постоянно). */
@Entity
@Table(name = "staff_limit")
@Getter
@Setter
@NoArgsConstructor
public class StaffLimit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** '1,2' = пн и вт. */
    @Column(nullable = false, length = 20)
    private String weekdays;

    /** null = весь день. */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_part", length = 10)
    private DayPart dayPart;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(length = 300)
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Set<Integer> days() {
        return Arrays.stream(weekdays.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Integer::valueOf).collect(Collectors.toSet());
    }

    /** Действует ли правило в этот день для этой части дня. */
    public boolean blocks(LocalDate d, DayPart part) {
        if (dateFrom != null && d.isBefore(dateFrom)) return false;
        if (dateTo != null && d.isAfter(dateTo)) return false;
        if (dayPart != null && dayPart != part) return false;
        return days().contains(d.getDayOfWeek().getValue());
    }
}