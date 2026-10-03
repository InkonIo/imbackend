package com.imdemo.im.review.service;

import com.imdemo.im.analytics.service.MetricsService;
import com.imdemo.im.domain.*;
import com.imdemo.im.dto.Dto.PhotoDto;
import com.imdemo.im.notify.domain.NotificationType;
import com.imdemo.im.notify.service.NotificationService;
import com.imdemo.im.repo.ChecklistPhotoRepository;
import com.imdemo.im.repo.ChecklistRunItemRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.review.domain.FlagDecision;
import com.imdemo.im.review.domain.ItemReview;
import com.imdemo.im.review.domain.ReviewDecision;
import com.imdemo.im.review.dto.ReviewDto.*;
import com.imdemo.im.review.repository.ItemReviewRepository;
import com.imdemo.im.review.repository.ReviewFlagRepository;
import com.imdemo.im.review.repository.ReviewItemQueryRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.AuditService;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final int QUEUE_LIMIT = 100;
    private static final List<FlagSeverity> SERIOUS = List.of(FlagSeverity.HIGH, FlagSeverity.MEDIUM);
    private static final List<Long> NO_FILTER = List.of(-1L);
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");

    private static final Map<FlagType, String> LABEL = Map.ofEntries(
            Map.entry(FlagType.TOO_FAST, "досрочно"),
            Map.entry(FlagType.SLOW, "дольше нормы"),
            Map.entry(FlagType.LATE, "опоздание"),
            Map.entry(FlagType.SKIPPED, "пропущен пункт"),
            Map.entry(FlagType.NOT_DONE, "не выполнен пункт"),
            Map.entry(FlagType.BURST, "пункты закрыты пачкой"),
            Map.entry(FlagType.OLD_PHOTO, "старое фото"),
            Map.entry(FlagType.DUPLICATE_PHOTO, "повтор фото"),
            Map.entry(FlagType.DEVICE_SWITCH, "другое устройство"),
            Map.entry(FlagType.IDLE_LONG, "долго нет активности"),
            Map.entry(FlagType.REJECTED, "отклонено директором"));

    private final ReviewFlagRepository flags;
    private final ReviewItemQueryRepository awaiting;
    private final ItemReviewRepository reviews;
    private final ShiftSessionRepository shifts;
    private final ChecklistRunItemRepository runItems;
    private final ChecklistPhotoRepository photos;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final MetricsService metrics;

    private record Scope(boolean all, Set<Long> outletIds) {
        boolean none() {
            return !all && outletIds.isEmpty();
        }

        boolean allows(Long outletId) {
            return all || outletIds.contains(outletId);
        }

        Collection<Long> idsForQuery() {
            return all || outletIds.isEmpty() ? NO_FILTER : outletIds;
        }
    }

    // ================= чтение =================

    @Transactional(readOnly = true)
    public QueueDto queue(UserPrincipal viewer) {
        Scope sc = scope(viewer);
        if (sc.none()) return new QueueDto(List.of(), List.of(), 0, 0);

        PageRequest page = PageRequest.of(0, QUEUE_LIMIT);
        List<AuditFlag> open = sc.all()
                ? flags.findByReviewStatusOrderByCreatedAtDesc(ReviewStatus.OPEN, page)
                : flags.findByReviewStatusAndOutletIdInOrderByCreatedAtDesc(ReviewStatus.OPEN, sc.outletIds(), page);
        List<ChecklistRunItem> waiting = awaiting.findAwaiting(
                RunItemStatus.PENDING, sc.all(), sc.idsForQuery(), page);

        Map<Long, ShiftSession> shiftCache = new HashMap<>();
        List<QueueFlagDto> flagDtos = open.stream()
                .map(f -> toDto(f, shiftCache.computeIfAbsent(f.getShiftId(),
                        id -> shifts.findById(id).orElseThrow())))
                .toList();
        List<QueueItemDto> itemDtos = waiting.stream().map(this::toDto).toList();

        SummaryDto s = summary(sc);
        return new QueueDto(itemDtos, flagDtos, s.openFlags(), s.awaitingItems());
    }

    @Transactional(readOnly = true)
    public SummaryDto summary(UserPrincipal viewer) {
        return summary(scope(viewer));
    }

    // ================= решения по флагам =================

    @Transactional
    public SummaryDto decideFlag(UserPrincipal viewer, Long flagId, FlagDecisionRequest r) {
        Scope sc = scope(viewer);
        AuditFlag f = flags.findById(flagId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Флаг не найден"));
        if (!sc.allows(f.getOutletId())) throw forbidden();
        String comment = requireReasonIfConfirm(r.decision(), r.comment());

        apply(f, r.decision(), viewer, comment);
        ShiftSession s = shifts.findById(f.getShiftId()).orElseThrow();
        String itemTitle = itemTitle(f.getRunItemId());

        audit.log(eventFor(r.decision()), s, "flag", f.getId(),
                f.getType() + dot(itemTitle) + dot(f.getDetails()) + dot(comment));

        if (r.decision() == FlagDecision.CONFIRM) {
            notifications.send(s.getUser().getId(), NotificationType.VIOLATION,
                    "🚩 Нарушение: " + LABEL.getOrDefault(f.getType(), f.getType().name()),
                    joinLines(itemTitle, f.getDetails(), "Директор: " + comment),
                    s.getId(), f.getRunItemId(), f.getId(), viewer.id());
        }
        metrics.recompute(s.getId());
        return summary(sc);
    }

    @Transactional
    public SummaryDto decideShift(UserPrincipal viewer, Long shiftId, FlagDecisionRequest r) {
        Scope sc = scope(viewer);
        ShiftSession s = shifts.findById(shiftId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Смена не найдена"));
        if (!sc.allows(s.getOutlet().getId())) throw forbidden();
        String comment = requireReasonIfConfirm(r.decision(), r.comment());

        List<AuditFlag> open = flags.findByShiftIdAndReviewStatus(shiftId, ReviewStatus.OPEN);
        open.forEach(f -> apply(f, r.decision(), viewer, comment));
        if (open.isEmpty()) return summary(sc);

        audit.log(eventFor(r.decision()), s, "shift", shiftId,
                "все флаги смены: " + open.size() + dot(comment));

        if (r.decision() == FlagDecision.CONFIRM) {
            String list = open.stream()
                    .map(f -> "• " + LABEL.getOrDefault(f.getType(), f.getType().name()) + dot(itemTitle(f.getRunItemId())))
                    .collect(Collectors.joining("\n"));
            notifications.send(s.getUser().getId(), NotificationType.VIOLATION,
                    "🚩 Нарушения за смену " + s.getShiftDate().format(DM) + ": " + open.size(),
                    joinLines(list, "Директор: " + comment),
                    s.getId(), null, null, viewer.id());
        }
        metrics.recompute(s.getId());
        return summary(sc);
    }

    // ================= решения по пунктам =================

    @Transactional
    public SummaryDto decideItem(UserPrincipal viewer, Long runItemId, ItemDecisionRequest r) {
        Scope sc = scope(viewer);
        ChecklistRunItem ri = runItems.findById(runItemId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пункт не найден"));
        ShiftSession s = ri.getRun().getShift();
        if (!sc.allows(s.getOutlet().getId())) throw forbidden();
        if (ri.getStatus() == RunItemStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Пункт ещё не закрыт");
        }
        String comment = blankToNull(r.comment());
        if (r.decision() == ReviewDecision.REJECTED && comment == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Напиши, что не так: сотрудник это увидит");
        }

        ItemReview review = reviews.findByRunItemId(runItemId).orElseGet(ItemReview::new);
        review.setRunItemId(runItemId);
        review.setShiftId(s.getId());
        review.setOutletId(s.getOutlet().getId());
        review.setDecision(r.decision());
        review.setComment(cut(comment, 500));
        review.setReviewerId(viewer.id());
        review.setReviewerLogin(viewer.login());
        review.setReviewedAt(OffsetDateTime.now());
        reviews.save(review);

        Optional<AuditFlag> rejected = flags.findFirstByRunItemIdAndType(runItemId, FlagType.REJECTED);
        Long flagId = null;
        if (r.decision() == ReviewDecision.REJECTED) {
            AuditFlag f = rejected.orElseGet(() -> {
                AuditFlag n = new AuditFlag();
                n.setShiftId(s.getId());
                n.setRunItemId(runItemId);
                n.setUserId(s.getUser().getId());
                n.setOutletId(s.getOutlet().getId());
                n.setType(FlagType.REJECTED);
                n.setSeverity(FlagType.REJECTED.getSeverity());
                return n;
            });
            f.setDetails(cut("отклонено директором: " + comment, 500));
            apply(f, FlagDecision.CONFIRM, viewer, comment);
            flagId = flags.save(f).getId();
        } else {
            rejected.ifPresent(f -> apply(f, FlagDecision.DISMISS, viewer, comment));
        }

        audit.log(r.decision() == ReviewDecision.APPROVED ? AuditEventType.ITEM_APPROVED : AuditEventType.ITEM_REJECTED,
                s, "run_item", runItemId, ri.getTitle() + dot(comment));

        if (r.decision() == ReviewDecision.REJECTED) {
            notifications.send(s.getUser().getId(), NotificationType.ITEM_REJECTED,
                    "❌ Неправильно: " + ri.getTitle(), "Директор: " + comment,
                    s.getId(), runItemId, flagId, viewer.id());
        } else {
            notifications.send(s.getUser().getId(), NotificationType.ITEM_APPROVED,
                    "✅ Директор принял: " + ri.getTitle(), comment == null ? null : "Директор: " + comment,
                    s.getId(), runItemId, null, viewer.id());
        }
        metrics.recompute(s.getId());
        return summary(sc);
    }

    // ================= внутреннее =================

    private SummaryDto summary(Scope sc) {
        if (sc.none()) return new SummaryDto(0, 0);
        long open = sc.all()
                ? flags.countByReviewStatusAndSeverityIn(ReviewStatus.OPEN, SERIOUS)
                : flags.countByReviewStatusAndSeverityInAndOutletIdIn(ReviewStatus.OPEN, SERIOUS, sc.outletIds());
        long items = awaiting.countAwaiting(RunItemStatus.PENDING, sc.all(), sc.idsForQuery());
        return new SummaryDto(open, items);
    }

    private Scope scope(UserPrincipal viewer) {
        if (viewer.role() == AccountRole.SUPER_ADMIN) return new Scope(true, Set.of());
        Set<Long> ids = users.findById(viewer.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
        return new Scope(false, ids);
    }

    private static String requireReasonIfConfirm(FlagDecision d, String comment) {
        String c = blankToNull(comment);
        if (d == FlagDecision.CONFIRM && c == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Напиши, в чём нарушение: сотрудник это увидит");
        }
        return c;
    }

    private String itemTitle(Long runItemId) {
        return runItemId == null ? null : runItems.findById(runItemId).map(ChecklistRunItem::getTitle).orElse(null);
    }

    private static void apply(AuditFlag f, FlagDecision d, UserPrincipal viewer, String comment) {
        f.setReviewStatus(d == FlagDecision.CONFIRM ? ReviewStatus.CONFIRMED : ReviewStatus.DISMISSED);
        f.setReviewedBy(viewer.id());
        f.setReviewedAt(OffsetDateTime.now());
        f.setReviewComment(cut(blankToNull(comment), 500));
    }

    private static AuditEventType eventFor(FlagDecision d) {
        return d == FlagDecision.CONFIRM ? AuditEventType.FLAG_CONFIRMED : AuditEventType.FLAG_DISMISSED;
    }

    private QueueFlagDto toDto(AuditFlag f, ShiftSession s) {
        ChecklistRunItem ri = f.getRunItemId() == null ? null : runItems.findById(f.getRunItemId()).orElse(null);
        List<PhotoDto> ph;
        if (f.getPhotoId() != null) {
            ph = photos.findById(f.getPhotoId()).map(p -> List.of(photo(p))).orElse(List.of());
        } else if (ri != null) {
            ph = ri.getPhotos().stream().map(ReviewService::photo).toList();
        } else {
            ph = List.of();
        }
        return new QueueFlagDto(f.getId(), f.getType(), f.getSeverity(), f.getDetails(), f.getCreatedAt(),
                s.getId(), s.getShiftDate(), s.getDayPart(), s.getUser().getFullName(), s.getOutlet().getName(),
                f.getRunItemId(), ri == null ? null : ri.getTitle(), ph);
    }

    private QueueItemDto toDto(ChecklistRunItem ri) {
        ShiftSession s = ri.getRun().getShift();
        return new QueueItemDto(ri.getId(), ri.getTitle(), ri.getStatus(), ri.getComment(), ri.getDoneAt(),
                s.getId(), s.getShiftDate(), s.getDayPart(), s.getUser().getFullName(), s.getOutlet().getName(),
                ri.getPhotos().stream().map(ReviewService::photo).toList());
    }

    private static PhotoDto photo(ChecklistPhoto p) {
        return new PhotoDto(p.getId(), "/api/checklist/photos/" + p.getId(), p.getUploadedAt());
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой точке");
    }

    private static String joinLines(String... parts) {
        return Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining("\n"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String dot(String s) {
        return s == null || s.isBlank() ? "" : " · " + s.trim();
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}