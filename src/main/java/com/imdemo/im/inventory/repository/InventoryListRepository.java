package com.imdemo.im.inventory.repository;

import com.imdemo.im.inventory.domain.InventoryList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryListRepository extends JpaRepository<InventoryList, Long> {
    List<InventoryList> findByActiveTrueOrderByIdAsc();
}