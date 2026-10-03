package com.imdemo.im.template.dto;

import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.PhotoMode;
import com.imdemo.im.domain.ShiftRole;
import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.util.List;

public final class TemplateDto {
    private TemplateDto() {}

    public record Summary(Long id, ShiftRole shiftRole, DayPart dayPart, Long outletId, String outletName,
                          String title, boolean active, int sections, int items, boolean editable) {}

    public record Item(Long id, Long sectionId, String title, String instructions, int sortOrder,
                       Integer durationMin, LocalTime dueFrom, LocalTime dueTo, PhotoMode photoMode,
                       Integer weekday, boolean directorReview, boolean active) {}

    public record Section(Long id, String title, int sortOrder, List<Item> items) {}

    public record Full(Summary summary, List<Section> sections) {}

    public record CreateRequest(@NotNull ShiftRole shiftRole, @NotNull DayPart dayPart, Long outletId,
                                @NotBlank @Size(max = 150) String title, Long copyFromId) {}

    public record UpdateRequest(@NotBlank @Size(max = 150) String title, Boolean active) {}

    public record SectionRequest(@NotBlank @Size(max = 150) String title) {}

    public record OrderRequest(@NotNull List<Long> ids) {}

    public record ItemRequest(Long sectionId,
                              @NotBlank @Size(max = 500) String title,
                              @Size(max = 2000) String instructions,
                              @Min(1) @Max(600) Integer durationMin,
                              LocalTime dueFrom,
                              LocalTime dueTo,
                              @NotNull PhotoMode photoMode,
                              @Min(1) @Max(7) Integer weekday,
                              boolean directorReview,
                              Boolean active) {}
}