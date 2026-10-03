package com.imdemo.im.repo;

import com.imdemo.im.domain.ShiftSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShiftSessionRepository extends JpaRepository<ShiftSession, Long> {
    Optional<ShiftSession> findFirstByUserIdAndFinishedAtIsNull(Long userId);

    List<ShiftSession> findByShiftDateOrderByStartedAtAsc(LocalDate shiftDate);

    List<ShiftSession> findByShiftDateAndOutletIdInOrderByStartedAtAsc(LocalDate shiftDate, Collection<Long> outletIds);
}