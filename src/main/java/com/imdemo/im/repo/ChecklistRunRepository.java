package com.imdemo.im.repo;

import com.imdemo.im.domain.ChecklistRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChecklistRunRepository extends JpaRepository<ChecklistRun, Long> {
    Optional<ChecklistRun> findByShiftId(Long shiftId);
}