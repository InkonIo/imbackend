package com.imdemo.im.events;

/** События, на которые подписываются фоновые обработчики (Telegram и т.п.). */
public final class AppEvents {
    private AppEvents() {}

    /** Сотруднику создано уведомление (🔔). */
    public record NotificationCreated(Long notificationId) {}

    /** Закрыт пункт «проверяет директор». */
    public record ReviewNeeded(Long runItemId) {}

    /** Детектор поднял серьёзный флаг. */
    public record FlagRaised(Long flagId) {}
}