package com.imdemo.im.inventory.repository;

import com.imdemo.im.inventory.domain.InventoryLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryLineRepository extends JpaRepository<InventoryLine, Long> {
}