package com.imdemo.im.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "checklist_run_item")
@Getter
@Setter
@NoArgsConstructor
public class ChecklistRunItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id")
    private ChecklistRun run;

    /** ссылка на исходный пункт шаблона (может стать null, если пункт удалят) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private ChecklistItem item;

    @Column(name = "section_order", nullable = false)
    private Integer sectionOrder;

    @Column(name = "section_title", nullable = false)
    private String sectionTitle;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(name = "due_from")
    private LocalTime dueFrom;

    @Column(name = "due_to")
    private LocalTime dueTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_mode", nullable = false)
    private PhotoMode photoMode;

    @Column(name = "director_review", nullable = false)
    private boolean directorReview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunItemStatus status = RunItemStatus.PENDING;

    @Column(length = 1000)
    private String comment;

    @Column(name = "done_at")
    private OffsetDateTime doneAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @OneToMany(mappedBy = "runItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("uploadedAt ASC")
    private List<ChecklistPhoto> photos = new ArrayList<>();

    @Column(length = 2000)
    private String instructions;
}