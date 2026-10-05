package com.imdemo.im.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.repo.AuditEventRepository;
import com.imdemo.im.repo.AuditFlagRepository;
import com.imdemo.im.repo.ChecklistPhotoRepository;
import com.imdemo.im.repo.ChecklistRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FlagService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DM_HM = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    // ---- пороги (меняются здесь) ----
    static final double TOO_FAST_RATIO = 0.3;
    static final double SLOW_RATIO = 1.5;
    static final int BURST_COUNT = 5;
    static final Duration BURST_WINDOW = Duration.ofSeconds(60);
    static final Duration OLD_PHOTO = Duration.ofMinutes(15);
    static final Duration IDLE_LIMIT = Duration.ofMinutes(120);

    private final org.springframework.context.ApplicationEventPublisher publisher;

    private static final Map<FlagType, String> LABEL = Map.of(
            FlagType.TOO_FAST, "досрочно",
            FlagType.SLOW, "дольше нормы",
            FlagType.LATE, "опоздание",
            FlagType.SKIPPED, "пропущен",
            FlagType.NOT_DONE, "не выполнен",
            FlagType.BURST, "пачкой",
            FlagType.OLD_PHOTO, "старое фото",
            FlagType.DUPLICATE_PHOTO, "повтор фото",
            FlagType.DEVICE_SWITCH, "другое устройство",
            FlagType.IDLE_LONG, "нет активности");

    private final AuditFlagRepository flags;
    private final ChecklistPhotoRepository photos;
    private final ChecklistRunRepository runs;
    private final AuditEventRepository events;
    private final ActivityStore activity;
    private final AuditService audit;

    // ================= точки вызова =================

    /** Пункт только что закрыт (DONE / PROBLEM / SKIPPED). doneAt уже выставлен. */
    @Transactional
    public void onItemClosed(ChecklistRunItem ri, OffsetDateTime now) {
        ShiftSession s = ri.getRun().getShift();

        if (ri.getStatus() == RunItemStatus.DONE && ri.getStartedAt() != null && ri.getDurationMin() != null) {
            long actual = Duration.between(ri.getStartedAt(), now).toMinutes();
            int norm = ri.getDurationMin();
            if (actual < norm * TOO_FAST_RATIO) {
                raiseOnce(s, ri, null, FlagType.TOO_FAST, actual + " мин при норме " + norm);
            } else if (actual > norm * SLOW_RATIO) {
                raiseOnce(s, ri, null, FlagType.SLOW, actual + " мин при норме " + norm);
            }
        }

        if (ri.getStatus() != RunItemStatus.SKIPPED && ri.getDueTo() != null) {
            OffsetDateTime due = s.getShiftDate().atTime(ri.getDueTo()).atZone(ZONE).toOffsetDateTime();
            long late = Duration.between(due, now).toMinutes();
            if (late > 0) {
                raiseOnce(s, ri, null, FlagType.LATE, "на " + human(Duration.ofMinutes(late))
                        + " позже срока " + ri.getDueTo().format(HM));
            }
        }

        if (ri.getStatus() == RunItemStatus.SKIPPED) {
            raiseOnce(s, ri, null, FlagType.SKIPPED, null);
        }

        checkBurst(s, ri.getRun(), now);
    }

    /** Фото сохранено и уже имеет id. */
    @Transactional
    public void onPhotoUploaded(ChecklistRunItem ri, ChecklistPhoto photo, OffsetDateTime now) {
        ShiftSession s = ri.getRun().getShift();

        if (photo.getTakenAt() != null) {
            Duration age = Duration.between(photo.getTakenAt(), now);
            if (age.compareTo(OLD_PHOTO) > 0) {
                raise(s, ri, photo, FlagType.OLD_PHOTO, "снято " + fmtDay(photo.getTakenAt())
                        + ", загружено через " + human(age), true);
            }
        }

        if (photo.getSha256() != null) {
            photos.findFirstBySha256AndIdNotOrderByIdAsc(photo.getSha256(), photo.getId()).ifPresent(prev -> {
                ChecklistRunItem prevItem = prev.getRunItem();
                raise(s, ri, photo, FlagType.DUPLICATE_PHOTO, "такое же фото уже было " + fmtDay(prev.getUploadedAt())
                        + " (смена #" + prevItem.getRun().getShift().getId() + ", «" + prevItem.getTitle() + "»)", true);
            });
        }
    }

    /** Действие пришло не с того устройства, с которого начинали смену. Флаг один на смену. */
    @Transactional
    public void checkDevice(ShiftSession s) {
        String current = AuditService.currentDeviceId();
        if (current == null || flags.existsByShiftIdAndType(s.getId(), FlagType.DEVICE_SWITCH)) return;
        events.findFirstByShiftIdAndTypeOrderByIdAsc(s.getId(), AuditEventType.SHIFT_STARTED)
                .map(AuditEvent::getDeviceId)
                .filter(start -> !start.equals(current))
                .ifPresent(start -> raise(s, null, null, FlagType.DEVICE_SWITCH,
                        "смену начали на #" + tail(start) + ", действие с #" + tail(current), true));
    }

    /** Пауза в активности между двумя моментами. */
    @Transactional
    public void onIdleGap(ShiftSession s, OffsetDateTime from, OffsetDateTime to) {
        Duration gap = Duration.between(from, to);
        if (gap.compareTo(IDLE_LIMIT) < 0) return;
        raise(s, null, null, FlagType.IDLE_LONG,
                "нет активности " + human(gap) + " (" + fmtTime(from) + "–" + fmtTime(to) + ")", true);
    }

    /** Смена завершается: незакрытые пункты и пауза до конца смены. */
    @Transactional
    public void onShiftFinished(ShiftSession s, OffsetDateTime now) {
        runs.findByShiftId(s.getId()).ifPresent(run -> {
            List<ChecklistRunItem> open = run.getItems().stream()
                    .filter(ri -> ri.getStatus() == RunItemStatus.PENDING).toList();
            for (ChecklistRunItem ri : open) {
                if (!flags.existsByRunItemIdAndType(ri.getId(), FlagType.NOT_DONE)) {
                    raise(s, ri, null, FlagType.NOT_DONE, null, false); // без отдельной строки в журнале на каждый
                }
            }
            if (!open.isEmpty()) {
                audit.log(AuditEventType.FLAG_RAISED, s, "shift", s.getId(),
                        "не выполнен · незакрытых пунктов: " + open.size());
            }
        });
        OffsetDateTime last = activity.lastMinute(s.getId());
        onIdleGap(s, last != null ? last : s.getStartedAt(), now);
    }

    // ================= правила =================

    private void checkBurst(ShiftSession s, ChecklistRun run, OffsetDateTime now) {
        OffsetDateTime since = now.minus(BURST_WINDOW);
        long recent = run.getItems().stream()
                .filter(i -> i.getStatus() != RunItemStatus.PENDING && i.getDoneAt() != null
                        && !i.getDoneAt().isBefore(since))
                .count();
        if (recent < BURST_COUNT) return;
        if (flags.existsByShiftIdAndTypeAndCreatedAtAfter(s.getId(), FlagType.BURST, now.minusMinutes(5))) return;
        raise(s, null, null, FlagType.BURST, recent + " пунктов закрыто за минуту", true);
    }

    // ================= запись =================

    private void raiseOnce(ShiftSession s, ChecklistRunItem ri, ChecklistPhoto photo, FlagType type, String details) {
        if (ri != null && flags.existsByRunItemIdAndType(ri.getId(), type)) return;
        raise(s, ri, photo, type, details, true);
    }

    private void raise(ShiftSession s, ChecklistRunItem ri, ChecklistPhoto photo, FlagType type,
                       String details, boolean logEvent) {
        AuditFlag f = new AuditFlag();
        f.setShiftId(s.getId());
        f.setRunItemId(ri == null ? null : ri.getId());
        f.setPhotoId(photo == null ? null : photo.getId());
        f.setUserId(s.getUser().getId());
        f.setOutletId(s.getOutlet().getId());
        f.setType(type);
        f.setSeverity(type.getSeverity());
        f.setDetails(AuditService.cut(details, 500));
        flags.save(f);

        if (logEvent) {
            StringBuilder text = new StringBuilder(LABEL.getOrDefault(type, type.name()));
            if (ri != null) text.append(" · ").append(ri.getTitle());
            if (details != null) text.append(" · ").append(details);
            audit.log(AuditEventType.FLAG_RAISED, s, "flag", f.getId(), text.toString());
        }
        if (logEvent && f.getSeverity() != FlagSeverity.LOW) {
            publisher.publishEvent(new com.imdemo.im.events.AppEvents.FlagRaised(f.getId()));
        }
    }

    // ================= формат =================

    private static String human(Duration d) {
        long min = Math.max(0, d.toMinutes());
        if (min < 60) return min + " мин";
        return (min / 60) + " ч " + (min % 60) + " мин";
    }

    private static String fmtTime(OffsetDateTime t) {
        return t.atZoneSameInstant(ZONE).format(HM);
    }

    private static String fmtDay(OffsetDateTime t) {
        return t.atZoneSameInstant(ZONE).format(DM_HM);
    }

    private static String tail(String deviceId) {
        return deviceId.length() <= 4 ? deviceId : deviceId.substring(deviceId.length() - 4);
    }
}