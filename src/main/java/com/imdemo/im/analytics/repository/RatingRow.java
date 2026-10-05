package com.imdemo.im.analytics.repository;

public interface RatingRow {
    Long getUserId();
    Long getShifts();
    Long getScore();
    Long getItemsTotal();
    Long getItemsCompleted();
    Long getDueTotal();
    Long getDueOnTime();
    Long getViolations();
    Long getPhotos();
    Long getPerfect();
}