package com.imdemo.im.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Время пункта внутри смены. Всё, что раньше 06:00, относится к следующим суткам (вечер после полуночи). */
public final class ShiftClock {
    private ShiftClock() {}

    public static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    public static final LocalTime DAY_START = LocalTime.of(6, 0);

    public static OffsetDateTime at(LocalDate shiftDate, LocalTime time) {
        LocalDate day = time.isBefore(DAY_START) ? shiftDate.plusDays(1) : shiftDate;
        return day.atTime(time).atZone(ZONE).toOffsetDateTime();
    }

    /** Порядок времени в «рабочих сутках» 06:00 → 05:59. Нужен для проверки «с» раньше «до». */
    public static int order(LocalTime time) {
        int m = time.getHour() * 60 + time.getMinute();
        return time.isBefore(DAY_START) ? m + 24 * 60 : m;
    }
}