package com.imdemo.im.analytics.service;

import com.imdemo.im.analytics.dto.RatingDto.Board;
import com.imdemo.im.analytics.dto.RatingDto.Entry;
import com.imdemo.im.analytics.repository.RatingRow;
import com.imdemo.im.analytics.repository.ShiftMetricsRepository;
import com.imdemo.im.domain.AppUser;
import com.imdemo.im.domain.ShiftRole;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RatingService {

    private final ShiftMetricsRepository metrics;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public Board board(LocalDate from, LocalDate to, ShiftRole role, Long outletId) {
        if (to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "Дата «по» раньше даты «с»");
        if (ChronoUnit.DAYS.between(from, to) > 366) throw new ApiException(HttpStatus.BAD_REQUEST, "Период не больше года");

        List<RatingRow> rows = outletId == null
                ? metrics.rating(from, to, role)
                : metrics.ratingForOutlet(from, to, role, outletId);

        Map<Long, AppUser> byId = users.findAllById(rows.stream().map(RatingRow::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));

        List<Entry> sorted = rows.stream().map(r -> {
                    long shifts = n(r.getShifts());
                    long score = n(r.getScore());
                    long dueTotal = n(r.getDueTotal());
                    AppUser u = byId.get(r.getUserId());
                    return new Entry(0, r.getUserId(), u == null ? "?" : u.getFullName(), shifts, score,
                            shifts == 0 ? 0 : Math.round(score * 10.0 / shifts) / 10.0,
                            pct(n(r.getItemsCompleted()), n(r.getItemsTotal())),
                            dueTotal == 0 ? null : pct(n(r.getDueOnTime()), dueTotal),
                            n(r.getViolations()), n(r.getPhotos()), n(r.getPerfect()));
                })
                .sorted(Comparator.comparingLong(Entry::score).reversed()
                        .thenComparing(Comparator.comparingDouble(Entry::avgScore).reversed())
                        .thenComparingLong(Entry::violations))
                .toList();

        // одинаковые баллы = одинаковое место
        List<Entry> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Entry e = sorted.get(i);
            int rank = (i > 0 && sorted.get(i - 1).score() == e.score()) ? ranked.get(i - 1).rank() : i + 1;
            ranked.add(new Entry(rank, e.userId(), e.name(), e.shifts(), e.score(), e.avgScore(),
                    e.completionPct(), e.onTimePct(), e.violations(), e.photos(), e.perfect()));
        }
        return new Board(from, to, role, outletId, ranked);
    }

    private static long n(Long v) {
        return v == null ? 0 : v;
    }

    private static int pct(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 100f / total);
    }
}