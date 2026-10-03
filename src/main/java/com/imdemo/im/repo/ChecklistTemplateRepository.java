package com.imdemo.im.repo;

import com.imdemo.im.domain.ChecklistTemplate;
import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.ShiftRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, Long> {
    Optional<ChecklistTemplate> findByShiftRoleAndDayPartAndActiveTrue(ShiftRole shiftRole, DayPart dayPart);
}