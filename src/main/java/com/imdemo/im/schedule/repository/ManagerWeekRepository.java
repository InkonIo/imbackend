package com.imdemo.im.schedule.repository;

import com.imdemo.im.schedule.domain.ManagerWeek;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ManagerWeekRepository extends JpaRepository<ManagerWeek, Long> {
    List<ManagerWeek> findByOutletIdOrderByWeekStartDesc(Long outletId);
    Optional<ManagerWeek> findByOutletIdAndWeekStart(Long outletId, LocalDate weekStart);
}