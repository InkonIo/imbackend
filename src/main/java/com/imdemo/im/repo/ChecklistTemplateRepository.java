package com.imdemo.im.repo;

import com.imdemo.im.domain.ChecklistTemplate;
import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.ShiftRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, Long> {

    Optional<ChecklistTemplate> findFirstByShiftRoleAndDayPartAndOutletIdAndActiveTrue(
            ShiftRole shiftRole, DayPart dayPart, Long outletId);

    Optional<ChecklistTemplate> findFirstByShiftRoleAndDayPartAndOutletIdIsNullAndActiveTrue(
            ShiftRole shiftRole, DayPart dayPart);

    boolean existsByShiftRoleAndDayPartAndOutletId(ShiftRole shiftRole, DayPart dayPart, Long outletId);

    boolean existsByShiftRoleAndDayPartAndOutletIdIsNull(ShiftRole shiftRole, DayPart dayPart);
}