package com.imdemo.im.review.dto;

import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.FlagSeverity;
import com.imdemo.im.domain.FlagType;
import com.imdemo.im.domain.RunItemStatus;
import com.imdemo.im.dto.Dto.PhotoDto;
import com.imdemo.im.review.domain.FlagDecision;
import com.imdemo.im.review.domain.ReviewDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ReviewDto {
    private ReviewDto() {}

    public record QueueFlagDto(Long flagId, FlagType type, FlagSeverity severity, String details,
                               OffsetDateTime createdAt, Long shiftId, LocalDate shiftDate, DayPart dayPart,
                               String userName, String outletName, Long runItemId, String itemTitle,
                               List<PhotoDto> photos) {}

    public record QueueItemDto(Long runItemId, String title, RunItemStatus status, String comment,
                               OffsetDateTime doneAt, Long shiftId, LocalDate shiftDate, DayPart dayPart,
                               String userName, String outletName, List<PhotoDto> photos) {}

    public record QueueDto(List<QueueItemDto> items, List<QueueFlagDto> flags,
                           long openFlags, long awaitingItems) {}

    public record SummaryDto(long openFlags, long awaitingItems) {}

    public record FlagDecisionRequest(@NotNull FlagDecision decision, @Size(max = 500) String comment) {}

    public record ItemDecisionRequest(@NotNull ReviewDecision decision, @Size(max = 500) String comment) {}
}