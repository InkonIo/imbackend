package com.imdemo.im.telegram.service;

import com.imdemo.im.analytics.dto.RatingDto.Board;
import com.imdemo.im.analytics.dto.RatingDto.Entry;
import com.imdemo.im.analytics.service.RatingService;
import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.AppUser;
import com.imdemo.im.domain.AuditFlag;
import com.imdemo.im.domain.ChecklistPhoto;
import com.imdemo.im.domain.ChecklistRunItem;
import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.FlagSeverity;
import com.imdemo.im.domain.FlagType;
import com.imdemo.im.domain.Outlet;
import com.imdemo.im.domain.ReviewStatus;
import com.imdemo.im.domain.RunItemStatus;
import com.imdemo.im.domain.ShiftRole;
import com.imdemo.im.domain.ShiftSession;
import com.imdemo.im.events.AppEvents;
import com.imdemo.im.notify.domain.Notification;
import com.imdemo.im.notify.repository.NotificationRepository;
import com.imdemo.im.repo.AuditFlagRepository;
import com.imdemo.im.repo.ChecklistPhotoRepository;
import com.imdemo.im.repo.ChecklistRunItemRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.review.repository.ItemReviewRepository;
import com.imdemo.im.service.PhotoStorage;
import com.imdemo.im.telegram.client.TelegramClient;
import com.imdemo.im.telegram.domain.TelegramLink;
import com.imdemo.im.telegram.repository.TelegramLinkRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Слушает события после коммита и рассылает их в Telegram. События по одному пункту склеиваются в одно сообщение. */
@Component
@RequiredArgsConstructor
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final Map<Integer, String> MEDAL = Map.of(1, "🥇", 2, "🥈", 3, "🥉");

    /** Сколько ждать после последнего события по пункту, прежде чем отправить. */
    private static final long DEBOUNCE_SEC = 8;
    /** Пункт ещё не закрыт: как часто перепроверять. */
    private static final long RECHECK_SEC = 60;
    /** Флаг по незакрытому пункту дольше этого ждать не будем. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(20);

    private final TelegramClient tg;
    private final TelegramLinkRepository links;
    private final NotificationRepository notifications;
    private final ChecklistRunItemRepository runItems;
    private final ChecklistPhotoRepository photos;
    private final AuditFlagRepository flags;
    private final ShiftSessionRepository shifts;
    private final UserRepository users;
    private final PhotoStorage storage;
    private final RatingService rating;
    private final ItemReviewRepository itemReviews;
    private final PlatformTransactionManager txManager;

    @Value("${app.telegram.notify-admins:false}")
    private boolean notifyAdmins;

    /** Накопитель событий по пункту: проверка директора + флаги. */
    private static final class Batch {
        final Instant created = Instant.now();
        boolean review;
        final Set<Long> flagIds = new HashSet<>();
        ScheduledFuture<?> future;
    }

    private final Map<Long, Batch> batches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-batch");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }

    // ================= сотруднику: всё, что в 🔔 =================

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onNotification(AppEvents.NotificationCreated e) {
        if (!tg.ready()) return;
        Notification n = notifications.findById(e.notificationId()).orElse(null);
        if (n == null) return;
        String text = n.getTitle() + (n.getBody() == null ? "" : "\n\n" + n.getBody());
        links.findByUserId(n.getUserId()).ifPresent(l -> safe(() -> tg.sendText(l.getChatId(), text)));
    }

    // ================= директору: пункт закрыт (проверка или 📲 инфо) =================

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewNeeded(AppEvents.ReviewNeeded e) {
        if (!tg.ready()) return;
        queue(e.runItemId(), true, null);
    }

    // ================= директору: серьёзный флаг =================

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onFlagRaised(AppEvents.FlagRaised e) {
        if (!tg.ready()) return;
        AuditFlag f = flags.findById(e.flagId()).orElse(null);
        if (f == null) return;
        if (f.getType() == FlagType.LATE) return; // опоздания видны в отчёте, в чат не спамим

        // флаг по пункту → в общее сообщение пункта
        if (f.getRunItemId() != null) {
            queue(f.getRunItemId(), false, f.getId());
            return;
        }

        // флаг без пункта (пачкой, другое устройство, нет активности) → короткое отдельное сообщение
        ShiftSession s = shifts.findById(f.getShiftId()).orElse(null);
        if (s == null) return;
        StringBuilder text = new StringBuilder("🚩 ").append(TelegramKeyboards.label(f.getType()))
                .append(f.getSeverity() == FlagSeverity.HIGH ? " · 🔴 серьёзно" : "");
        if (f.getDetails() != null) text.append("\n").append(f.getDetails());
        text.append("\n\n").append(who(s));
        var kb = TelegramKeyboards.decision('f', f.getId());
        for (long chatId : reviewerChats(s.getOutlet().getId())) {
            safe(() -> tg.sendText(chatId, text.toString(), kb));
        }
    }

    // ================= склейка событий по пункту =================

    private void queue(long runItemId, boolean review, Long flagId) {
        Batch b = batches.computeIfAbsent(runItemId, k -> new Batch());
        synchronized (b) {
            if (review) b.review = true;
            if (flagId != null) b.flagIds.add(flagId);
            if (b.future != null) b.future.cancel(false);
            b.future = timer.schedule(() -> flush(runItemId), DEBOUNCE_SEC, TimeUnit.SECONDS);
        }
    }

    private void flush(long runItemId) {
        Batch b = batches.get(runItemId);
        if (b == null) return;
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setReadOnly(true);

            // пункт ещё не закрыт (флаг пришёл при загрузке фото) → подождём закрытия
            Boolean wait = tx.execute(st -> runItems.findById(runItemId)
                    .map(ri -> ri.getStatus() == RunItemStatus.PENDING
                            && Duration.between(b.created, Instant.now()).compareTo(MAX_WAIT) < 0)
                    .orElse(false));
            if (Boolean.TRUE.equals(wait)) {
                synchronized (b) {
                    b.future = timer.schedule(() -> flush(runItemId), RECHECK_SEC, TimeUnit.SECONDS);
                }
                return;
            }

            batches.remove(runItemId, b);
            boolean review;
            Set<Long> flagIds;
            synchronized (b) {
                review = b.review;
                flagIds = Set.copyOf(b.flagIds);
            }
            tx.executeWithoutResult(st -> sendItem(runItemId, review, flagIds));
        } catch (Exception e) {
            batches.remove(runItemId, b);
            log.warn("Telegram: не удалось отправить пункт {}: {}", runItemId, e.getMessage());
        }
    }

    /** Одно сообщение по пункту: фото, что сделано, все флаги, кнопки. */
    private void sendItem(long runItemId, boolean review, Set<Long> flagIds) {
        ChecklistRunItem ri = runItems.findById(runItemId).orElse(null);
        if (ri == null) return;
        ShiftSession s = ri.getRun().getShift();

        List<AuditFlag> open = flagIds.isEmpty() ? List.of() : flags.findAllById(flagIds).stream()
                .filter(f -> f.getReviewStatus() == ReviewStatus.OPEN)
                .sorted(Comparator.comparing(AuditFlag::getId))
                .toList();
        boolean closed = ri.getStatus() == RunItemStatus.DONE || ri.getStatus() == RunItemStatus.PROBLEM;
        boolean needReview = review && closed && ri.isDirectorReview() && itemReviews.findByRunItemId(ri.getId()).isEmpty();
        if (!review && open.isEmpty()) return; // флаги уже решили на сайте, слать нечего

        StringBuilder text = new StringBuilder(needReview ? "👁 Проверь выполнение" : review ? "📸 Выполнено" : "🚩 Нарушение")
                .append("\n").append(ri.getTitle());
        if (ri.getStatus() == RunItemStatus.PROBLEM) text.append("\n⚠️ Сотрудник отметил проблему");
        if (ri.getComment() != null) text.append("\n💬 ").append(ri.getComment());
        if (!open.isEmpty()) {
            text.append("\n");
            for (AuditFlag f : open) {
                text.append("\n🚩 ").append(TelegramKeyboards.label(f.getType()))
                        .append(f.getSeverity() == FlagSeverity.HIGH ? " · 🔴" : "");
                if (f.getDetails() != null) text.append(" — ").append(f.getDetails());
            }
        }
        text.append("\n\n").append(who(s));
        if (ri.getDoneAt() != null) text.append(" · закрыто в ").append(hm(ri.getDoneAt()));

        // фото пункта + фото из флагов, если их нет среди фото пункта
        Set<Long> have = ri.getPhotos().stream().map(ChecklistPhoto::getId).collect(Collectors.toSet());
        List<Path> files = new ArrayList<>(ri.getPhotos().stream().map(p -> storage.resolve(p.getFilePath())).toList());
        for (AuditFlag f : open) {
            if (f.getPhotoId() != null && !have.contains(f.getPhotoId())) {
                photos.findById(f.getPhotoId()).ifPresent(p -> files.add(storage.resolve(p.getFilePath())));
            }
        }
        List<Path> limited = files.stream().limit(5).toList();

        var kb = TelegramKeyboards.combined(needReview ? ri.getId() : null, open);
        List<Long> chats = reviewerChats(s.getOutlet().getId());
        log.info("Telegram: «{}» одним сообщением (проверка: {}, флагов: {}), получателей: {}",
                ri.getTitle(), needReview, open.size(), chats.size());
        for (long chatId : chats) {
            safe(() -> sendWithPhotos(chatId, limited, text.toString(), kb));
        }
    }

    // ================= всем: итоги недели, понедельник 9:00 =================

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Almaty")
    @Transactional(readOnly = true)
    public void weeklyPodium() {
        if (!tg.ready()) return;
        LocalDate from = LocalDate.now(ZONE).minusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate to = from.plusDays(6);

        for (TelegramLink l : links.findAll()) {
            AppUser u = users.findById(l.getUserId()).orElse(null);
            if (u == null || !u.isActive()) continue;
            Outlet outlet = u.getOutlets().stream().findFirst().orElse(null);
            Board b = rating.board(from, to, ShiftRole.INSIDE, outlet == null ? null : outlet.getId());
            if (b.entries().isEmpty()) continue;

            StringBuilder sb = new StringBuilder("🏆 Итоги недели ")
                    .append(from.format(DM)).append("–").append(to.format(DM))
                    .append(outlet == null ? "" : " · " + outlet.getName()).append("\n\n");
            for (Entry e : b.entries().stream().filter(x -> x.rank() <= 3).toList()) {
                sb.append(MEDAL.getOrDefault(e.rank(), "•")).append(' ').append(e.name())
                        .append(" — ").append(e.score()).append(" б.\n");
            }
            b.entries().stream().filter(x -> x.userId().equals(u.getId())).findFirst().ifPresent(me -> sb
                    .append(me.rank() <= 3 ? "\n🎉 Ты на подиуме!" : "\nТы на " + me.rank() + " месте: " + me.score() + " б."));
            safe(() -> tg.sendText(l.getChatId(), sb.toString()));
        }
    }

    // ================= внутреннее =================

    /** Директора этой точки (и суперадмины, если включено), у которых привязан Telegram. */
    private List<Long> reviewerChats(Long outletId) {
        Set<Long> ids = users.findAll().stream()
                .filter(AppUser::isActive)
                .filter(u -> (u.getAccountRole() == AccountRole.DIRECTOR
                        && u.getOutlets().stream().anyMatch(o -> o.getId().equals(outletId)))
                        || (notifyAdmins && u.getAccountRole() == AccountRole.SUPER_ADMIN))
                .map(AppUser::getId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return List.of();
        return links.findByUserIdIn(ids).stream().map(TelegramLink::getChatId).toList();
    }

    private void sendWithPhotos(long chatId, List<Path> files, String text, Map<String, Object> kb) {
        List<Path> existing = files.stream().filter(Files::exists).toList();
        try {
            if (existing.size() == 1) {
                tg.sendPhoto(chatId, existing.get(0), text, kb);
                return;
            }
            if (existing.size() > 1) tg.sendMediaGroup(chatId, existing);
        } catch (Exception e) {
            log.warn("Telegram фото не ушло ({}), отправляю текстом", e.getMessage());
        }
        tg.sendText(chatId, text, kb);
    }

        private String who(ShiftSession s) {
        return s.getUser().getFullName() + " · " + s.getOutlet().getName() + " · "
                + s.getDayPart().label() + " " + s.getShiftDate().format(DM);
    }

    private static String hm(OffsetDateTime t) {
        return t == null ? "—" : t.atZoneSameInstant(ZONE).format(HM);
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Telegram: {}", e.getMessage());
        }
    }
}