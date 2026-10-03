package com.imdemo.im.analytics.repository;

/** Сумма метрик сотрудника за период (проекция для GROUP BY). */
public interface UserAggregate {
    Long getUserId();
    Long getShifts();
    Long getItemsTotal();
    Long getItemsCompleted();
    Long getItemsSkipped();
    Long getItemsNotDone();
    Long getDueTotal();
    Long getDueOnTime();
    Long getPhotos();
    Long getComments();
    Long getFlags();
    Long getFlagsConfirmed();
    Long getTooFast();
    Long getLate();
    Long getRejected();
    Long getActiveMin();
}