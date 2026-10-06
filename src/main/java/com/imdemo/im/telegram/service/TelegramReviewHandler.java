package com.imdemo.im.telegram.service;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.AppUser;
import com.imdemo.im.domain.AuditFlag;
import com.imdemo.im.domain.ChecklistRunItem;
import com.imdemo.im.domain.FlagSeverity;
import com.imdemo.im.domain.FlagType;
import com.imdemo.im.domain.ReviewStatus;
import com.imdemo.im.domain.RunItemStatus;
import com.imdemo.im.repo.ChecklistRunItemRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.review.domain.FlagDecision;
import com.imdemo.im.review.domain.ReviewDecision;
import com.imdemo.im.review.dto.ReviewDto.FlagDecisionRequest;
import com.imdemo.im.review.dto.ReviewDto.ItemDecisionRequest;
import com.imdemo.im.review.repository.ItemReviewRepository;
import com.imdemo.im.review.repository.ReviewFlagRepository;
import com.imdemo.im.review.service.ReviewService;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.telegram.client.Tg;
import com.imdemo.im.telegram.client.TelegramClient;
import com.imdemo.im.telegram.repository.TelegramLinkRepository;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Решения директора прямо в Telegram. Вызывает тот же ReviewService, что и сайт. */
@Component
@RequiredArgsConstructor
public class TelegramReviewHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramReviewHandler.class);

    private final TelegramClient tg;
    private final TelegramLinkRepository links;
    private final UserRepository users;
    private final ReviewService reviews;
    private final ReviewFlagRepository flagRepo;
    private final ItemReviewRepository itemReviews;
    private final ChecklistRunItemRepository runItems;

    /** Ждём «свою причину» текстом: chatId → что решаем. */
    private record Pending(char kind, long id, Long messageId, Instant until) {}

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    public void onCallback(Tg.CallbackQuery q) {
        if (q.message() == null || q.data() == null) {
            tg.answer(q.id(), "Кнопка устарела", false);
            return;
        }
        long chatId = q.message().chat().id();
        Long msgId = q.message().messageId();
        String[] p = q.data().split(":");
        try {
            UserPrincipal viewer = reviewer(chatId);
            switch (p[0]) {
                case "ia" -> decide(chatId, msgId, q.id(), viewer, 'i', id(p[1]), true, null);
                case "fd" -> decide(chatId, msgId, q.id(), viewer, 'f', id(p[1]), true, null);
                case "ir", "fc" -> {
                    char kind = p[0].charAt(0) == 'i' ? 'i' : 'f';
                    long id = id(p[1]);
                    ensureOpen(kind, id);
                    tg.editReplyMarkup(chatId, msgId, TelegramKeyboards.reasons(kind, id));
                    tg.answer(q.id(), "Выбери причину: сотрудник её увидит", false);
                }
                case "rs" -> {
                    String reason = TelegramKeyboards.REASONS.get(Integer.parseInt(p[3]));
                    decide(chatId, msgId, q.id(), viewer, p[1].charAt(0), id(p[2]), false, reason);
                }
                case "rw" -> {
                    char kind = p[1].charAt(0);
                    long id = id(p[2]);
                    ensureOpen(kind, id);
                    pending.put(chatId, new Pending(kind, id, msgId, Instant.now().plus(10, ChronoUnit.MINUTES)));
                    tg.answer(q.id(), null, false);
                    tg.sendForceReply(chatId, "✍️ Напиши причину одним сообщением. Сотрудник её увидит.");
                }
                case "rx" -> {
                    pending.remove(chatId);
                    // вернуть все оставшиеся кнопки сообщения, а не только одну строку
                    tg.editReplyMarkup(chatId, msgId, currentKeyboard(p[1].charAt(0), id(p[2])));
                    tg.answer(q.id(), "Отменено", false);
                }
                default -> tg.answer(q.id(), "Неизвестная кнопка", false);
            }
        } catch (ApiException e) {
            tg.answer(q.id(), e.getMessage(), true);
            // уже решено (на сайте или другим директором) → показать только то, что ещё осталось
            if (e.getStatus() == HttpStatus.CONFLICT) safe(() -> refresh(chatId, msgId, p));
        } catch (Exception e) {
            log.warn("Telegram callback {}: {}", q.data(), e.getMessage());
            tg.answer(q.id(), "Не получилось. Попробуй в панели на сайте", true);
        }
    }

    /** Текстовое сообщение. true, если это была «своя причина». */
    public boolean onText(long chatId, String text) {
        Pending p = pending.remove(chatId);
        if (p == null || p.until().isBefore(Instant.now()) || text.startsWith("/")) return false;
        try {
            decide(chatId, p.messageId(), null, reviewer(chatId), p.kind(), p.id(), false, text.trim());
        } catch (ApiException e) {
            tg.sendText(chatId, "⚠️ " + e.getMessage());
        } catch (Exception e) {
            log.warn("Telegram reason: {}", e.getMessage());
            tg.sendText(chatId, "⚠️ Не получилось. Попробуй в панели на сайте");
        }
        return true;
    }

    // ---------------- внутреннее ----------------

    /** positive: пункт принят / флаг снят. Иначе: не принят / нарушение (нужна причина). */
    private void decide(long chatId, Long msgId, String callbackId, UserPrincipal viewer,
                        char kind, long id, boolean positive, String reason) {
        ensureOpen(kind, id);
        asUser(viewer, () -> {
            if (kind == 'i') {
                reviews.decideItem(viewer, id, new ItemDecisionRequest(
                        positive ? ReviewDecision.APPROVED : ReviewDecision.REJECTED, reason));
            } else {
                reviews.decideFlag(viewer, id, new FlagDecisionRequest(
                        positive ? FlagDecision.DISMISS : FlagDecision.CONFIRM, reason));
            }
        });

        // убрать только решённую строку, остальные кнопки оставить
        safe(() -> tg.editReplyMarkup(chatId, msgId, currentKeyboard(kind, id)));

        String result = kind == 'i'
                ? (positive ? "✅ Принято" : "❌ Не принято")
                : (positive ? "👌 Флаг снят" : "🚩 Нарушение подтверждено");
        if (reason != null) result += "\nПричина: " + reason + "\n🔔 Сотрудник получил обратную связь";
        String text = result;
        safe(() -> tg.sendText(chatId, text, null, msgId));
        if (callbackId != null) tg.answer(callbackId, "Сохранено", false);
    }

    /** Что ещё можно решить по сообщению: строка проверки пункта + открытые флаги. null = ничего. */
    private Map<String, Object> currentKeyboard(char kind, long id) {
        Long runItemId = kind == 'i' ? Long.valueOf(id)
                : flagRepo.findById(id).map(AuditFlag::getRunItemId).orElse(null);

        if (runItemId == null) {
            // флаг без пункта (пачкой, другое устройство…): одна строка, пока не решён
            boolean open = kind == 'f' && flagRepo.findById(id)
                    .map(f -> f.getReviewStatus() == ReviewStatus.OPEN)
                    .orElse(false);
            return open ? TelegramKeyboards.decision('f', id) : null;
        }

        ChecklistRunItem ri = runItems.findById(runItemId).orElse(null);
        boolean closed = ri != null
                && (ri.getStatus() == RunItemStatus.DONE || ri.getStatus() == RunItemStatus.PROBLEM);
        Long reviewId = ri != null && closed && ri.isDirectorReview()
                && itemReviews.findByRunItemId(runItemId).isEmpty() ? runItemId : null;

        List<AuditFlag> open = flagRepo.findByRunItemIdOrderByIdAsc(runItemId).stream()
                .filter(f -> f.getReviewStatus() == ReviewStatus.OPEN)
                .filter(f -> f.getSeverity() != FlagSeverity.LOW)
                .filter(f -> f.getType() != FlagType.LATE)
                .toList();

        return TelegramKeyboards.combined(reviewId, open);
    }

    /** Перерисовать кнопки по данным нажатой кнопки (после «уже проверено» и т.п.). */
    private void refresh(long chatId, Long msgId, String[] p) {
        char kind;
        long id;
        switch (p[0]) {
            case "ia", "ir" -> {
                kind = 'i';
                id = id(p[1]);
            }
            case "fc", "fd" -> {
                kind = 'f';
                id = id(p[1]);
            }
            default -> { // rs:K:ID:N, rw:K:ID, rx:K:ID
                kind = p[1].charAt(0);
                id = id(p[2]);
            }
        }
        tg.editReplyMarkup(chatId, msgId, currentKeyboard(kind, id));
    }

    private void ensureOpen(char kind, long id) {
        if (kind == 'f') {
            AuditFlag f = flagRepo.findById(id)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Флаг не найден"));
            if (f.getReviewStatus() != ReviewStatus.OPEN) {
                throw new ApiException(HttpStatus.CONFLICT, f.getReviewStatus() == ReviewStatus.CONFIRMED
                        ? "Уже проверено: нарушение подтверждено" : "Уже проверено: флаг снят");
            }
        } else {
            itemReviews.findByRunItemId(id).ifPresent(r -> {
                throw new ApiException(HttpStatus.CONFLICT, r.getDecision() == ReviewDecision.APPROVED
                        ? "Уже проверено: принято" : "Уже проверено: не принято");
            });
        }
    }

    private UserPrincipal reviewer(long chatId) {
        AppUser u = links.findByChatId(chatId)
                .flatMap(l -> users.findById(l.getUserId()))
                .filter(AppUser::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Этот Telegram не привязан к аккаунту"));
        if (u.getAccountRole() != AccountRole.DIRECTOR && u.getAccountRole() != AccountRole.SUPER_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Проверять могут только директор и суперадмин");
        }
        return new UserPrincipal(u.getId(), u.getLogin(), u.getAccountRole());
    }

    /** Чтобы журнал записал действие от имени директора, ставим его как текущего пользователя. */
    private static void asUser(UserPrincipal p, Runnable action) {
        var auth = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static long id(String s) {
        return Long.parseLong(s);
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception ignored) {
            // сообщение могло быть удалено или слишком старым для правки
        }
    }
}