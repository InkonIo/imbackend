package com.imdemo.im.repo;

import com.imdemo.im.domain.AuditFlag;
import com.imdemo.im.domain.FlagType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface AuditFlagRepository extends JpaRepository<AuditFlag, Long> {
    boolean existsByRunItemIdAndType(Long runItemId, FlagType type);

    boolean existsByShiftIdAndType(Long shiftId, FlagType type);

    boolean existsByShiftIdAndTypeAndCreatedAtAfter(Long shiftId, FlagType type, OffsetDateTime after);

    List<AuditFlag> findByShiftIdOrderByCreatedAtAsc(Long shiftId);

    List<AuditFlag> findByShiftIdIn(Collection<Long> shiftIds);
}