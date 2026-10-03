package com.imdemo.im.notify.dto;

import com.imdemo.im.notify.domain.NotificationType;

import java.time.OffsetDateTime;
import java.util.List;

public final class NotificationDto {
    private NotificationDto() {}

    public record Item(Long id, NotificationType type, String title, String body,
                       Long shiftId, Long runItemId, Long flagId,
                       OffsetDateTime createdAt, OffsetDateTime readAt) {}

    public record ListDto(List<Item> items, long unread) {}

    public record Unread(long unread) {}
}