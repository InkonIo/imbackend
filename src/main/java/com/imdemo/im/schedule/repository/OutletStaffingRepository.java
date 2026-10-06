// OutletStaffingRepository.java
package com.imdemo.im.schedule.repository;

import com.imdemo.im.schedule.domain.OutletStaffing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutletStaffingRepository extends JpaRepository<OutletStaffing, Long> {
}