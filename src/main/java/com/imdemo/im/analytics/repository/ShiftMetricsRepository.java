package com.imdemo.im.analytics.repository;

import com.imdemo.im.analytics.domain.ShiftMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ShiftMetricsRepository extends JpaRepository<ShiftMetrics, Long> {

    @Query("""
            select m.userId as userId,
                   count(m) as shifts,
                   sum(m.itemsTotal) as itemsTotal,
                   sum(m.itemsDone + m.itemsProblem) as itemsCompleted,
                   sum(m.itemsSkipped) as itemsSkipped,
                   sum(m.itemsNotDone) as itemsNotDone,
                   sum(m.dueTotal) as dueTotal,
                   sum(m.dueOnTime) as dueOnTime,
                   sum(m.photosTotal) as photos,
                   sum(m.commentsTotal) as comments,
                   sum(m.flagsTotal) as flags,
                   sum(m.flagsConfirmed) as flagsConfirmed,
                   sum(m.tooFast) as tooFast,
                   sum(m.late) as late,
                   sum(m.itemsRejected) as rejected,
                   sum(m.activeMin) as activeMin
            from ShiftMetrics m
            where m.shiftDate between :from and :to and m.finishedAt is not null
            group by m.userId
            """)
    List<UserAggregate> aggregateByUser(@Param("from") LocalDate from, @Param("to") LocalDate to);
}