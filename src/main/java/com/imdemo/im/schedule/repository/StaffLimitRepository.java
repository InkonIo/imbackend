package com.imdemo.im.schedule.repository;

import com.imdemo.im.schedule.domain.StaffLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StaffLimitRepository extends JpaRepository<StaffLimit, Long> {
    List<StaffLimit> findByUserIdOrderByIdAsc(Long userId);

    List<StaffLimit> findByUserIdIn(Collection<Long> userIds);
}