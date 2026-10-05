package com.imdemo.im.telegram.service;

import com.imdemo.im.analytics.dto.RatingDto.Board;
import com.imdemo.im.analytics.dto.RatingDto.Entry;
import com.imdemo.im.analytics.service.RatingService;
import com.imdemo.im.domain.*;
import com.imdemo.im.events.AppEvents;
import com.imdemo.im.notify.domain.Notification;
import com.imdemo.im.notify.repository.NotificationRepository;
import com.imdemo.im.repo.*;
import com.imdemo.im.service.PhotoStorage;
import com.imdemo.im.telegram.client.TelegramClient;
import com.imdemo.im.telegram.domain.TelegramLink;
import com.imdemo.im.telegram.repository.TelegramLinkRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Слушает события после коммита и рассылает их в Telegram в фоне. */
@Component
@RequiredArgsConstructor
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final Map<Integer, String> MEDAL = Map.of(1, "🥇", 2, "🥈", 3, "🥉");

    private static final Map<FlagType, String> FLAG_LABEL = Map.ofEntries(
            Map.entry(FlagType.TOO_FAST, "⚡ досрочно"),
            Map.entry(FlagType.SLOW, "🐢 дольше нормы"),
            Map.entry(FlagType.LATE, "⏰ опоздание"),
            Map.entry(FlagType.SKIPPED, "⏭️ пропущен пункт"),
            Map.entry(FlagType.NOT_DONE, "❌ не выполнен"),
            Map.entry(FlagType.BURST, "🌀 пункты пачкой"),
            Map.entry(FlagType.OLD_PHOTO, "🕰️ старое фото"),
            Map.entry(FlagType.DUPLICATE_PHOTO, "👯 повтор фото"),
            Map.entry(FlagType.DEVICE_SWITCH, "📱 другое устройство"),
            Map.entry(FlagType.IDLE_LONG, "💤 нет активности"),
            Map.entry(FlagType.REJECTED, "🙅 отклонено директором"));

    private final TelegramClient tg;
    private final TelegramLinkRepository links;
    private final NotificationRepository notifications;
    private final ChecklistRunItemRepository runItems;
    private final ChecklistPhotoRepository photos;
    private final AuditFlagRepository flags;
    private final ShiftSessionRepository shifts;
    private final UserRepository users;
    private final OutletRepository outlets;
    private final PhotoStorage storage;
    private final RatingService rating;

    @Value("${app.telegram.notify-admins:false}")
    private boolean notifyAdmins;

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

    // ================= директору: пункт на проверку =================

        @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReviewNeeded(AppEvents.ReviewNeeded e) {
        if (!tg.ready()) return;
        ChecklistRunItem ri = runItems.findById(e.runItemId()).orElse(null);
        if (ri == null) return;
        ShiftSession s = ri.getRun().getShift();
        boolean review = ri.isDirectorReview(); // кнопки только у пунктов «проверяет директор»

        StringBuilder text = new StringBuilder(review ? "👁 Проверь выполнение\n" : "📸 Выполнено\n")
                .append(ri.getTitle());
        if (ri.getStatus() == RunItemStatus.PROBLEM) text.append("\n⚠️ Сотрудник отметил проблему");
        if (ri.getComment() != null) text.append("\n💬 ").append(ri.getComment());
        text.append("\n\n").append(who(s)).append(" · закрыто в ").append(hm(ri.getDoneAt()));

        List<Path> files = ri.getPhotos().stream().map(p -> storage.resolve(p.getFilePath())).limit(5).toList();
        var kb = review ? TelegramKeyboards.decision('i', ri.getId()) : null;

        List<Long> chats = reviewerChats(s.getOutlet().getId());
        log.info("Telegram: «{}» ({}), фото: {}, получателей: {}",
                ri.getTitle(), review ? "на проверку" : "инфо", files.size(), chats.size());
        for (long chatId : chats) {
            safe(() -> sendWithPhotos(chatId, files, text.toString(), kb));
        }
    }

    // ================= директору: серьёзный флаг =================

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onFlagRaised(AppEvents.FlagRaised e) {
        if (!tg.ready()) return;
        AuditFlag f = flags.findById(e.flagId()).orElse(null);
        if (f == null) return;
        ShiftSession s = shifts.findById(f.getShiftId()).orElse(null);
        if (s == null) return;
        ChecklistRunItem ri = f.getRunItemId() == null ? null : runItems.findById(f.getRunItemId()).orElse(null);

        StringBuilder text = new StringBuilder("🚩 ")
                .append(FLAG_LABEL.getOrDefault(f.getType(), f.getType().name()))
                .append(f.getSeverity() == FlagSeverity.HIGH ? " · 🔴 серьёзно" : "");
        if (ri != null) text.append("\n").append(ri.getTitle());
        if (f.getDetails() != null) text.append("\n").append(f.getDetails());
        text.append("\n\n").append(who(s));

        List<Path> files;
        if (f.getPhotoId() != null) {
            files = photos.findById(f.getPhotoId()).map(p -> List.of(storage.resolve(p.getFilePath()))).orElse(List.of());
        } else if (ri != null) {
            files = ri.getPhotos().stream().map(p -> storage.resolve(p.getFilePath())).limit(5).toList();
        } else {
            files = List.of();
        }

        var kb = TelegramKeyboards.decision('f', f.getId());
        for (long chatId : reviewerChats(s.getOutlet().getId())) {
            safe(() -> sendWithPhotos(chatId, files, text.toString(), kb));
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
                + (s.getDayPart() == DayPart.MORNING ? "🌅 Утро" : "🌙 Вечер") + " " + s.getShiftDate().format(DM);
    }

    private static String hm(OffsetDateTime t) {
        return t == null ? "—" : t.atZoneSameInstant(ZONE).format(HM);
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Telegram: {}", e.getMessage()); // например, пользователь заблокировал бота
        }
    }
}