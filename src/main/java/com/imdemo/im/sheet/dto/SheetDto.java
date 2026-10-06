package com.imdemo.im.sheet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

public final class SheetDto {
    private SheetDto() {}

    public record Sheet(Long id, Long outletId, String outletName, LocalDate date,
                        String morningName, String eveningName, boolean editable,
                        Map<String, String> values) {}

    /** Пакет изменённых клеток: ключ → значение (null или пусто = очистить). */
    public record SaveRequest(@NotNull @Size(max = 300) Map<String, String> values) {}

    public record Saved(OffsetDateTime updatedAt) {}
}