package com.imdemo.im.telegram.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Тонкий клиент Telegram Bot API: только то, что нужно проекту. */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    private static final ParameterizedTypeReference<Tg.Response<Object>> ANY = new ParameterizedTypeReference<>() {};

    private final String token;
    private final String base;
    private final RestClient http;
    private volatile String botUsername;

    public TelegramClient(@Value("${app.telegram.token:}") String token) {
        this.token = token == null ? "" : token.trim();
        this.base = "https://api.telegram.org/bot" + this.token + "/";
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60)); // long polling держит соединение ~25 сек
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        return !token.isEmpty();
    }

    /** Токен есть и бот успешно представился. */
    public boolean ready() {
        return configured() && botUsername != null;
    }

    public String botUsername() {
        return botUsername;
    }

    /** Сбросить вебхук (иначе getUpdates не работает) и узнать имя бота. */
    public void init() {
        post("deleteWebhook", Map.of("drop_pending_updates", false), ANY);
        Tg.User me = post("getMe", Map.of(), new ParameterizedTypeReference<Tg.Response<Tg.User>>() {});
        botUsername = me.username();
    }

    public List<Tg.Update> getUpdates(long offset, int timeoutSec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", offset);
        body.put("timeout", timeoutSec);
        body.put("allowed_updates", List.of("message", "callback_query"));
        List<Tg.Update> list = post("getUpdates", body,
                new ParameterizedTypeReference<Tg.Response<List<Tg.Update>>>() {});
        return list == null ? List.of() : list;
    }

    // ---------------- отправка ----------------

    public void sendText(long chatId, String text) {
        sendText(chatId, text, null, null);
    }

    public void sendText(long chatId, String text, Map<String, Object> keyboard) {
        sendText(chatId, text, keyboard, null);
    }

    public void sendText(long chatId, String text, Map<String, Object> keyboard, Long replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", cut(text, 4000));
        body.put("reply_markup", keyboard);
        if (replyTo != null) body.put("reply_parameters", Map.of("message_id", replyTo, "allow_sending_without_reply", true));
        post("sendMessage", body, ANY);
    }

    /** Сообщение, на которое Telegram сразу предложит ответить (для «своей причины»). */
    public void sendForceReply(long chatId, String text) {
        sendText(chatId, text, Map.of("force_reply", true, "input_field_placeholder", "Причина…"), null);
    }

    public void sendPhoto(long chatId, Path file, String caption, Map<String, Object> keyboard) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat_id", String.valueOf(chatId));
        if (caption != null) form.add("caption", cut(caption, 1000));
        if (keyboard != null) form.add("reply_markup", Json.write(keyboard));
        form.add("photo", new FileSystemResource(file));
        multipart("sendPhoto", form);
    }

    public void sendMediaGroup(long chatId, List<Path> files) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat_id", String.valueOf(chatId));
        List<Map<String, Object>> media = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            media.add(Map.of("type", "photo", "media", "attach://p" + i));
            form.add("p" + i, new FileSystemResource(files.get(i)));
        }
        form.add("media", Json.write(media));
        multipart("sendMediaGroup", form);
    }

    /** Заменить или убрать (keyboard = null) кнопки под сообщением. */
    public void editReplyMarkup(long chatId, Long messageId, Map<String, Object> keyboard) {
        if (messageId == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("reply_markup", keyboard != null ? keyboard : Map.of("inline_keyboard", List.of()));
        post("editMessageReplyMarkup", body, ANY);
    }

    public void answer(String callbackId, String text, boolean alert) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callback_query_id", callbackId);
        body.put("text", text == null ? null : cut(text, 190));
        body.put("show_alert", alert);
        try {
            post("answerCallbackQuery", body, ANY);
        } catch (Exception e) {
            log.debug("answerCallbackQuery: {}", e.getMessage());
        }
    }

    // ---------------- клавиатуры ----------------

    public static Map<String, Object> keyboard(List<List<Map<String, Object>>> rows) {
        return Map.of("inline_keyboard", rows);
    }

    public static Map<String, Object> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }

    // ---------------- HTTP ----------------

    private <T> T post(String method, Map<String, Object> body, ParameterizedTypeReference<Tg.Response<T>> type) {
        Tg.Response<T> r = http.post()
                .uri(URI.create(base + method))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(body))
                .retrieve()
                .body(type);
        if (r == null || !r.ok()) {
            throw new IllegalStateException("Telegram " + method + ": " + (r == null ? "пустой ответ" : r.description()));
        }
        return r.result();
    }

    private void multipart(String method, MultiValueMap<String, Object> form) {
        Tg.Response<Object> r = http.post()
                .uri(URI.create(base + method))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(ANY);
        if (r == null || !r.ok()) {
            throw new IllegalStateException("Telegram " + method + ": " + (r == null ? "пустой ответ" : r.description()));
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}