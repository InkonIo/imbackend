package com.imdemo.im.sheet.repository;

import com.imdemo.im.sheet.domain.ShiftSheet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ShiftSheetRepository extends JpaRepository<ShiftSheet, Long> {
    Optional<ShiftSheet> findByOutletIdAndSheetDate(Long outletId, LocalDate sheetDate);
}