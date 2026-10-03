package com.imdemo.im.review.repository;

import com.imdemo.im.domain.ChecklistRunItem;
import com.imdemo.im.domain.RunItemStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Пункты «проверяет директор», которые закрыты, но ещё не проверены. */
public interface ReviewItemQueryRepository extends JpaRepository<ChecklistRunItem, Long> {

    String AWAITING = """
            from ChecklistRunItem ri
            where ri.directorReview = true
              and ri.status <> :pending
              and (:all = true or ri.run.shift.outlet.id in :outletIds)
              and not exists (select ir.id from ItemReview ir where ir.runItemId = ri.id)
            """;

    @Query("select ri " + AWAITING + " order by ri.doneAt desc")
    List<ChecklistRunItem> findAwaiting(@Param("pending") RunItemStatus pending,
                                        @Param("all") boolean all,
                                        @Param("outletIds") Collection<Long> outletIds,
                                        Pageable page);

    @Query("select count(ri) " + AWAITING)
    long countAwaiting(@Param("pending") RunItemStatus pending,
                       @Param("all") boolean all,
                       @Param("outletIds") Collection<Long> outletIds);
}