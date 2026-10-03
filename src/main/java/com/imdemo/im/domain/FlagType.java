package com.imdemo.im.domain;

public enum FlagType {
    TOO_FAST(FlagSeverity.HIGH),
    OLD_PHOTO(FlagSeverity.HIGH),
    DUPLICATE_PHOTO(FlagSeverity.HIGH),
    LATE(FlagSeverity.MEDIUM),
    SKIPPED(FlagSeverity.MEDIUM),
    NOT_DONE(FlagSeverity.MEDIUM),
    BURST(FlagSeverity.MEDIUM),
    DEVICE_SWITCH(FlagSeverity.MEDIUM),
    SLOW(FlagSeverity.LOW),
    IDLE_LONG(FlagSeverity.LOW);

    private final FlagSeverity severity;

    FlagType(FlagSeverity severity) {
        this.severity = severity;
    }

    public FlagSeverity getSeverity() {
        return severity;
    }
}