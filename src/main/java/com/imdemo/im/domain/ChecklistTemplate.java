package com.imdemo.im.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "checklist_template")
@Getter
@Setter
@NoArgsConstructor
public class ChecklistTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_role", nullable = false)
    private ShiftRole shiftRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_part", nullable = false)
    private DayPart dayPart;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ChecklistSection> sections = new ArrayList<>();

    /** null = общий маршрут для всех точек */
    @Column(name = "outlet_id")
    private Long outletId;
}