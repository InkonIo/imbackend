package com.imdemo.im.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

@Entity
@Table(name = "checklist_item")
@Getter
@Setter
@NoArgsConstructor
public class ChecklistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id")
    private ChecklistSection section;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(name = "due_from")
    private LocalTime dueFrom;

    @Column(name = "due_to")
    private LocalTime dueTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_mode", nullable = false)
    private PhotoMode photoMode = PhotoMode.NONE;

    /** 1 = пн … 7 = вс; null = каждый день */
    @Column(name = "weekday")
    private Integer weekday;

    @Column(name = "director_review", nullable = false)
    private boolean directorReview;

    @Column(nullable = false)
    private boolean active = true;
}