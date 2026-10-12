package com.imdemo.im.ext;

import com.imdemo.im.TimetrackerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Сам подтягивает график из Таймтрекера, чтобы «Общий график» не отставал на дни:
 * замены и правки, сделанные в Таймтрекере, появляются на сайте через несколько минут.
 * Загружает текущий месяц и, в последнюю неделю месяца, следующий.
 * Отключить: timetracker.autosync.minutes=0 в application.properties / .env (TIMETRACKER_AUTOSYNC_MINUTES=0).
 */
@Configuration
@EnableScheduling
public class TtAutoSync {

    private static final Logger log = LoggerFactory.getLogger(TtAutoSync.class);
    private final TimetrackerService tt;
    private final long minutes;

    public TtAutoSync(TimetrackerService tt, @Value("${timetracker.autosync.minutes:20}") long minutes) {
        this.tt = tt;
        this.minutes = minutes;
    }

    private volatile long lastRun = 0;

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void run() {
        if (minutes <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRun < minutes * 60_000L - 30_000L) return;
        lastRun = now;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Almaty"));
        YearMonth ym = YearMonth.from(today);
        try {
            tt.syncSchedule(ym.toString());
            if (today.getDayOfMonth() <= 10) tt.syncSchedule(ym.minusMonths(1).toString()); // правки конца прошлого месяца
            if (today.plusDays(7).getMonth() != today.getMonth()) tt.syncSchedule(ym.plusMonths(1).toString());
            log.info("TT autosync ok: {}", ym);
        } catch (Exception e) {
            // нет логина/пароля или Таймтрекер недоступен — не страшно, повторим в следующий раз
            log.warn("TT autosync пропущен: {}", e.getMessage());
        }
    }
}