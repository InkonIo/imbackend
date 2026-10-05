package com.imdemo.im.telegram.service;

import com.imdemo.im.domain.AppUser;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.telegram.client.TelegramClient;
import com.imdemo.im.telegram.domain.TelegramLink;
import com.imdemo.im.telegram.domain.TelegramLinkCode;
import com.imdemo.im.telegram.repository.TelegramLinkCodeRepository;
import com.imdemo.im.telegram.repository.TelegramLinkRepository;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TelegramLinkService {

    private static final Duration CODE_TTL = Duration.ofMinutes(15);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Status(boolean botReady, boolean linked, String username, String botUsername) {}

    public record Link(String url, OffsetDateTime expiresAt) {}

    private final TelegramLinkRepository links;
    private final TelegramLinkCodeRepository codes;
    private final UserRepository users;
    private final TelegramClient tg;

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        Optional<TelegramLink> link = links.findByUserId(userId);
        return new Status(tg.ready(), link.isPresent(), link.map(TelegramLink::getUsername).orElse(null), tg.botUsername());
    }

    @Transactional
    public Link createLink(Long userId) {
        if (!tg.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Telegram-бот не настроен на сервере");
        OffsetDateTime now = OffsetDateTime.now();
        codes.deleteForUserOrExpired(userId, now);

        TelegramLinkCode c = new TelegramLinkCode();
        c.setCode(randomCode(20));
        c.setUserId(userId);
        c.setExpiresAt(now.plus(CODE_TTL));
        codes.save(c);
        return new Link("https://t.me/" + tg.botUsername() + "?start=" + c.getCode(), c.getExpiresAt());
    }

    @Transactional
    public void unlink(Long userId) {
        links.findByUserId(userId).ifPresent(links::delete);
    }

    @Transactional
    public boolean unlinkChat(long chatId) {
        Optional<TelegramLink> link = links.findByChatId(chatId);
        link.ifPresent(links::delete);
        return link.isPresent();
    }

    /** /start КОД: привязать чат к пользователю. Пустой результат = код неверный или устарел. */
    @Transactional
    public Optional<AppUser> consume(String code, long chatId, String username) {
        TelegramLinkCode c = codes.findById(code).orElse(null);
        if (c == null) return Optional.empty();
        codes.delete(c);
        if (c.getExpiresAt().isBefore(OffsetDateTime.now())) return Optional.empty();

        AppUser user = users.findById(c.getUserId()).filter(AppUser::isActive).orElse(null);
        if (user == null) return Optional.empty();

        // один Telegram = один аккаунт на сайте
        links.findByChatId(chatId).ifPresent(old -> {
            links.delete(old);
            links.flush();
        });
        TelegramLink link = links.findByUserId(user.getId()).orElseGet(TelegramLink::new);
        link.setUserId(user.getId());
        link.setChatId(chatId);
        link.setUsername(username);
        link.setLinkedAt(OffsetDateTime.now());
        links.save(link);
        return Optional.of(user);
    }

    private static String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}