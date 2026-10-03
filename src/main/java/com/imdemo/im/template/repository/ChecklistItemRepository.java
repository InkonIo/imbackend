package com.imdemo.im.template.repository;

import com.imdemo.im.domain.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {
}