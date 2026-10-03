package com.imdemo.im.analytics.service;

import com.imdemo.im.analytics.dto.AnalyticsDto.UserStats;
import com.imdemo.im.analytics.repository.ShiftMetricsRepository;
import com.imdemo.im.analytics.repository.UserAggregate;
import com.imdemo.im.domain.AppUser;
import com.imdemo.im.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final ShiftMetricsRepository metrics;
    private final UserRepository users;

    /** Сводка по сотрудникам за период. Основа для будущего дашборда и рейтинга. */
    @Transactional(readOnly = true)
    public List<UserStats> users(LocalDate from, LocalDate to) {
        List<UserAggregate> rows = metrics.aggregateByUser(from, to);
        Map<Long, AppUser> byId = users.findAllById(rows.stream().map(UserAggregate::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));

        return rows.stream().map(r -> {
            AppUser u = byId.get(r.getUserId());
            long dueTotal = n(r.getDueTotal());
            return new UserStats(r.getUserId(),
                    u == null ? "?" : u.getFullName(), u == null ? null : u.getLogin(),
                    n(r.getShifts()), n(r.getItemsTotal()),
                    pct(n(r.getItemsCompleted()), n(r.getItemsTotal())),
                    dueTotal == 0 ? null : pct(n(r.getDueOnTime()), dueTotal),
                    n(r.getItemsSkipped()), n(r.getItemsNotDone()),
                    n(r.getPhotos()), n(r.getComments()),
                    n(r.getFlags()), n(r.getFlagsConfirmed()),
                    n(r.getTooFast()), n(r.getLate()), n(r.getRejected()),
                    n(r.getActiveMin()));
        }).sorted(Comparator.comparing((UserStats s) -> s.completionPct() == null ? 0 : s.completionPct()).reversed()
                .thenComparingLong(UserStats::flagsConfirmed)).toList();
    }

    private static long n(Long v) {
        return v == null ? 0 : v;
    }

    private static Integer pct(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 100f / total);
    }
}