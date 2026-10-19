package com.imdemo.im.schedule.repository;

import com.imdemo.im.schedule.domain.ManagerScheduleEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ManagerScheduleRepository extends JpaRepository<ManagerScheduleEntry, Long> {
    List<ManagerScheduleEntry> findByWeekId(Long weekId);
    void deleteByWeekIdAndUserId(Long weekId, Long userId);
    Optional<ManagerScheduleEntry> findByWeekIdAndUserIdAndSchedDate(Long weekId, Long userId, LocalDate schedDate);
}
