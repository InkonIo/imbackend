package com.imdemo.im.review.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "item_review")
@Getter
@Setter
@NoArgsConstructor
public class ItemReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_item_id", nullable = false, unique = true)
    private Long runItemId;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewDecision decision;

    @Column(length = 500)
    private String comment;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(name = "reviewer_login")
    private String reviewerLogin;

    @Column(name = "reviewed_at", nullable = false)
    private OffsetDateTime reviewedAt = OffsetDateTime.now();
}