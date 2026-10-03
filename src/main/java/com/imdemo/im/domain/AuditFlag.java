package com.imdemo.im.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_flag")
@Getter
@Setter
@NoArgsConstructor
public class AuditFlag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "run_item_id")
    private Long runItemId;

    @Column(name = "photo_id")
    private Long photoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlagType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlagSeverity severity;

    @Column(length = 500)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus = ReviewStatus.OPEN;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;
}