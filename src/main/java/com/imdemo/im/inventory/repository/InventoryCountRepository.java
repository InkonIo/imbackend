package com.imdemo.im.inventory.repository;

import com.imdemo.im.inventory.domain.CountStatus;
import com.imdemo.im.inventory.domain.InventoryCount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryCountRepository extends JpaRepository<InventoryCount, Long> {

    Optional<InventoryCount> findByOutletIdAndListIdAndCountDate(Long outletId, Long listId, LocalDate countDate);

    boolean existsByOutletIdAndCountDateAndStatus(Long outletId, LocalDate countDate, CountStatus status);

    List<InventoryCount> findByCountDateBetweenOrderByCountDateDescIdDesc(LocalDate from, LocalDate to);

    List<InventoryCount> findByCountDateBetweenAndOutletIdInOrderByCountDateDescIdDesc(
            LocalDate from, LocalDate to, Collection<Long> outletIds);
}