// StaffAbsenceRepository.java
package com.imdemo.im.schedule.repository;

import com.imdemo.im.schedule.domain.StaffAbsence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StaffAbsenceRepository extends JpaRepository<StaffAbsence, Long> {

    @Query("""
            select a from StaffAbsence a
            where a.userId in :userIds and a.dateFrom <= :to and a.dateTo >= :from
            order by a.dateFrom
            """)
    List<StaffAbsence> overlapping(@Param("userIds") Collection<Long> userIds,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to);
}