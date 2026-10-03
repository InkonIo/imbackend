package com.imdemo.im.review.repository;

import com.imdemo.im.domain.AuditFlag;
import com.imdemo.im.domain.FlagSeverity;
import com.imdemo.im.domain.FlagType;
import com.imdemo.im.domain.ReviewStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Запросы к флагам, нужные только для проверки. */
public interface ReviewFlagRepository extends JpaRepository<AuditFlag, Long> {

    List<AuditFlag> findByReviewStatusOrderByCreatedAtDesc(ReviewStatus status, Pageable page);

    List<AuditFlag> findByReviewStatusAndOutletIdInOrderByCreatedAtDesc(
            ReviewStatus status, Collection<Long> outletIds, Pageable page);

    long countByReviewStatusAndSeverityIn(ReviewStatus status, Collection<FlagSeverity> severities);

    long countByReviewStatusAndSeverityInAndOutletIdIn(
            ReviewStatus status, Collection<FlagSeverity> severities, Collection<Long> outletIds);

    List<AuditFlag> findByShiftIdAndReviewStatus(Long shiftId, ReviewStatus status);

    Optional<AuditFlag> findFirstByRunItemIdAndType(Long runItemId, FlagType type);
}