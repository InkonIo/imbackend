package com.imdemo.im.review.repository;

import com.imdemo.im.review.domain.ItemReview;
import com.imdemo.im.review.domain.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ItemReviewRepository extends JpaRepository<ItemReview, Long> {
    Optional<ItemReview> findByRunItemId(Long runItemId);

    long countByShiftIdAndDecision(Long shiftId, ReviewDecision decision);
}