package com.imdemo.im.sheet.repository;

import com.imdemo.im.sheet.domain.ShiftSheetValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ShiftSheetValueRepository extends JpaRepository<ShiftSheetValue, Long> {
    List<ShiftSheetValue> findBySheetId(Long sheetId);

    List<ShiftSheetValue> findBySheetIdAndFieldKeyIn(Long sheetId, Collection<String> keys);
}