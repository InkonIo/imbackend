package com.imdemo.im.repo;

import com.imdemo.im.domain.ChecklistRunItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistRunItemRepository extends JpaRepository<ChecklistRunItem, Long> {
}