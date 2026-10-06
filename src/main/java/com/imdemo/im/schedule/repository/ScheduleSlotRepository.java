// ScheduleSlotRepository.java
package com.imdemo.im.schedule.repository;

import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.ShiftRole;
import com.imdemo.im.schedule.domain.ScheduleSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScheduleSlotRepository extends JpaRepository<ScheduleSlot, Long> {

    List<ScheduleSlot> findByOutletIdAndSlotDateBetweenOrderBySlotDateAsc(Long outletId, LocalDate from, LocalDate to);

    Optional<ScheduleSlot> findByOutletIdAndSlotDateAndDayPartAndRole(
            Long outletId, LocalDate date, DayPart dayPart, ShiftRole role);

    List<ScheduleSlot> findByUserIdInAndSlotDateBetween(Collection<Long> userIds, LocalDate from, LocalDate to);

    List<ScheduleSlot> findByUserIdAndSlotDateBetweenAndPublishedTrueOrderBySlotDateAsc(
            Long userId, LocalDate from, LocalDate to);

    boolean existsByUserIdAndOutletIdAndSlotDateAndDayPartAndRoleAndPublishedTrue(
            Long userId, Long outletId, LocalDate date, DayPart dayPart, ShiftRole role);
}