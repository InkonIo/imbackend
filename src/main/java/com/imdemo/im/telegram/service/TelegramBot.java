package com.imdemo.im.telegram.service;

import com.imdemo.im.telegram.client.Tg;
import com.imdemo.im.telegram.client.TelegramClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Long polling: бэкенд сам забирает обновления у Telegram. Вебхук и HTTPS не нужны. */
@Component
@RequiredArgsConstructor
public class TelegramBot implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private static final String HELP = """
            👋 Это бот IM Inside.

            Чтобы подключиться: открой сайт → 🔔 → «Подключить Telegram».

            Директор получает сюда фото и нарушения с кнопками ✅ / ❌.
            Сотрудники получают обратную связь и итоги недели 🏆.

            /stop — отключить бота""";

    private final TelegramClient tg;
    private final TelegramLinkService linking;
    private final TelegramReviewHandler review;

    private volatile boolean running;
    private Thread thread;
    private long offset;

    @Override
    public void start() {
        if (!tg.configured()) {
            log.warn("TELEGRAM_BOT_TOKEN не задан: Telegram-бот выключен");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "telegram-poller");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running && !tg.ready()) {
            try {
                tg.init();
                log.info("Telegram-бот @{} запущен", tg.botUsername());
            } catch (Exception e) {
                log.error("Telegram: не удалось подключиться ({}). Повтор через 30 сек", e.getMessage());
                sleep(30_000);
            }
        }
        while (running) {
            try {
                for (Tg.Update u : tg.getUpdates(offset, 25)) {
                    offset = u.updateId() + 1;
                    try {
                        handle(u);
                    } catch (Exception e) {
                        log.warn("Telegram update {}: {}", u.updateId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                if (!running) break;
                log.warn("Telegram polling: {}", e.getMessage());
                sleep(5_000);
            }
        }
    }

    private void handle(Tg.Update u) {
        if (u.callbackQuery() != null) {
            review.onCallback(u.callbackQuery());
            return;
        }
        Tg.Message m = u.message();
        if (m == null || m.text() == null) return;
        long chatId = m.chat().id();
        String text = m.text().trim();

        if (text.startsWith("/start")) {
            String code = text.length() > 6 ? text.substring(6).trim() : "";
            if (code.isEmpty()) {
                tg.sendText(chatId, HELP);
                return;
            }
            String username = m.from() == null ? null : m.from().username();
            linking.consume(code, chatId, username).ifPresentOrElse(
                    user -> tg.sendText(chatId, "✅ Готово, " + user.getFullName() + "!\n\n"
                            + "Теперь замечания, проверки и итоги недели будут приходить сюда.\n/stop — отключить"),
                    () -> tg.sendText(chatId, "⏰ Ссылка устарела или уже использована.\n"
                            + "Открой сайт → 🔔 → «Подключить Telegram» ещё раз."));
            return;
        }
        if (text.equals("/stop")) {
            tg.sendText(chatId, linking.unlinkChat(chatId)
                    ? "🔕 Отключено. Подключить снова можно на сайте в 🔔."
                    : "Этот чат и так не подключён.");
            return;
        }
        if (review.onText(chatId, text)) return;
        tg.sendText(chatId, HELP);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}