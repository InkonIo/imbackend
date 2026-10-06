package com.imdemo.im.domain;

public enum DayPart {
    MORNING("🌅 Утро"),
    EVENING("🌙 Вечер"),
    /** Промеж: между утром и вечером, своё время, только кухня и прилавок. */
    MIDDLE("🌤 Промеж");

    private final String label;

    DayPart(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}