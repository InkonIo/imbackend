package com.imdemo.im.inventory.dto;

import com.imdemo.im.inventory.domain.CountStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class InventoryDto {
    private InventoryDto() {}

    public record Line(Long id, String zone, int sortOrder, String name, String packText,
                       Double caseQty, Double sleeveQty, String unit, boolean decimal,
                       Double cs, Double slv, Double ea, boolean none, Double total, boolean filled,
                       Double prevCs, Double prevSlv, Double prevEa, Double prevTotal, LocalDate prevDate,
                       String warning, boolean confirmed) {}

    public record Count(Long id, String listCode, String listTitle, Long outletId, String outletName,
                        LocalDate date, Long userId, String userName, CountStatus status,
                        OffsetDateTime startedAt, OffsetDateTime submittedAt,
                        int total, int filled, int warnings, boolean editable, List<Line> lines) {}

    public record Summary(Long id, String listTitle, String outletName, LocalDate date, String userName,
                          CountStatus status, OffsetDateTime submittedAt,
                          int total, int filled, int recounted) {}

    public record StartRequest(Long outletId, LocalDate date) {}

    public record LineRequest(@PositiveOrZero @Max(9999) Double cs,
                              @PositiveOrZero @Max(9999) Double slv,
                              @PositiveOrZero @Max(99999) Double ea,
                              boolean none,
                              boolean confirmed) {}
}