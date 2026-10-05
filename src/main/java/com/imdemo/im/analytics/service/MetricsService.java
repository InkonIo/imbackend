package com.imdemo.im.analytics.service;

import com.imdemo.im.analytics.domain.ShiftMetrics;
import com.imdemo.im.analytics.repository.ShiftMetricsRepository;
import com.imdemo.im.domain.*;
import com.imdemo.im.repo.AuditFlagRepository;
import com.imdemo.im.repo.ChecklistRunRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.review.domain.ReviewDecision;
import com.imdemo.im.review.repository.ItemReviewRepository;
import com.imdemo.im.service.ActivityStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Predicate;

/** Считает итоговую строку метрик по смене. Вызывается в конце смены и после решений директора. */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

    private final ShiftSessionRepository shifts;
    private final ChecklistRunRepository runs;
    private final AuditFlagRepository flags;
    private final ItemReviewRepository reviews;
    private final ActivityStore activity;
    private final ShiftMetricsRepository metrics;

    @Transactional
    public void recompute(Long shiftId) {
        ShiftSession s = shifts.findById(shiftId).orElse(null);
        if (s == null) return;

        ShiftMetrics m = metrics.findById(shiftId).orElseGet(ShiftMetrics::new);
        m.setShiftId(shiftId);
        m.setUserId(s.getUser().getId());
        m.setOutletId(s.getOutlet().getId());
        m.setShiftDate(s.getShiftDate());
        m.setDayPart(s.getDayPart());
        m.setShiftRole(s.getShiftRole());
        m.setStartedAt(s.getStartedAt());
        m.setFinishedAt(s.getFinishedAt());
        m.setDurationMin(s.getFinishedAt() == null ? null
                : (int) Duration.between(s.getStartedAt(), s.getFinishedAt()).toMinutes());
        m.setActiveMin(activity.activeMinutes(List.of(shiftId)).getOrDefault(shiftId, 0));

        // ---- пункты ----
        List<ChecklistRunItem> items = runs.findByShiftId(shiftId).map(ChecklistRun::getItems).orElse(List.of());
        int total = items.size();
        int done = count(items, i -> i.getStatus() == RunItemStatus.DONE);
        int problem = count(items, i -> i.getStatus() == RunItemStatus.PROBLEM);
        int skipped = count(items, i -> i.getStatus() == RunItemStatus.SKIPPED);
        int notDone = s.getFinishedAt() == null ? 0 : count(items, i -> i.getStatus() == RunItemStatus.PENDING);
        m.setItemsTotal(total);
        m.setItemsDone(done);
        m.setItemsProblem(problem);
        m.setItemsSkipped(skipped);
        m.setItemsNotDone(notDone);
        m.setCompletionPct(pct(done + problem, total));

        // ---- сроки «до HH:mm» ----
        List<ChecklistRunItem> withDue = items.stream()
                .filter(i -> i.getDueTo() != null && i.getDoneAt() != null
                        && (i.getStatus() == RunItemStatus.DONE || i.getStatus() == RunItemStatus.PROBLEM))
                .toList();
        int onTime = count(withDue, i -> !i.getDoneAt().isAfter(
                s.getShiftDate().atTime(i.getDueTo()).atZone(ZONE).toOffsetDateTime()));
        m.setDueTotal(withDue.size());
        m.setDueOnTime(onTime);
        m.setOnTimePct(withDue.isEmpty() ? null : pct(onTime, withDue.size()));

        m.setPhotosTotal(items.stream().mapToInt(i -> i.getPhotos().size()).sum());
        m.setCommentsTotal(count(items, i -> i.getComment() != null && !i.getComment().isBlank()));

        // ---- флаги: снятые директором как ложные в нарушения не считаем ----
        List<AuditFlag> fl = flags.findByShiftIdOrderByCreatedAtAsc(shiftId);
        Predicate<AuditFlag> real = f -> f.getReviewStatus() != ReviewStatus.DISMISSED;
        m.setFlagsTotal(countF(fl, real));
        m.setFlagsHigh(countF(fl, real.and(f -> f.getSeverity() == FlagSeverity.HIGH)));
        m.setFlagsOpen(countF(fl, f -> f.getReviewStatus() == ReviewStatus.OPEN));
        m.setFlagsConfirmed(countF(fl, f -> f.getReviewStatus() == ReviewStatus.CONFIRMED));
        m.setFlagsDismissed(countF(fl, f -> f.getReviewStatus() == ReviewStatus.DISMISSED));
        m.setTooFast(countF(fl, real.and(f -> f.getType() == FlagType.TOO_FAST)));
        m.setLate(countF(fl, real.and(f -> f.getType() == FlagType.LATE)));

        // ---- решения директора по пунктам ----
        m.setItemsApproved((int) reviews.countByShiftIdAndDecision(shiftId, ReviewDecision.APPROVED));
        m.setItemsRejected((int) reviews.countByShiftIdAndDecision(shiftId, ReviewDecision.REJECTED));

                // подтверждённые нарушения по серьёзности (пропуск и невыполнение уже штрафуются отдельно)
        m.setConfirmedHigh(countF(fl, f -> f.getReviewStatus() == ReviewStatus.CONFIRMED
                && f.getSeverity() == FlagSeverity.HIGH));
        m.setConfirmedMedium(countF(fl, f -> f.getReviewStatus() == ReviewStatus.CONFIRMED
                && f.getSeverity() == FlagSeverity.MEDIUM
                && f.getType() != FlagType.SKIPPED && f.getType() != FlagType.NOT_DONE));

        // баллы только у завершённых смен
        m.setScore(s.getFinishedAt() == null ? null : ScoreRules.score(m));
        m.setComputedAt(OffsetDateTime.now());
        metrics.save(m);
    }

    /** Пересчитать все смены: после изменения формулы или для старых данных. */
    @Transactional
    public int rebuildAll() {
        List<ShiftSession> all = shifts.findAll();
        all.forEach(s -> recompute(s.getId()));
        return all.size();
    }

    private static int count(List<ChecklistRunItem> list, Predicate<ChecklistRunItem> p) {
        return (int) list.stream().filter(p).count();
    }

    private static int countF(List<AuditFlag> list, Predicate<AuditFlag> p) {
        return (int) list.stream().filter(p).count();
    }

    private static int pct(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 100f / total);
    }
}