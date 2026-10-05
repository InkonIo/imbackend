package com.imdemo.im.telegram.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Нужные нам поля из ответов Telegram. Остальные игнорируются. */
public final class Tg {
    private Tg() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response<T>(boolean ok, T result, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, String username, @JsonProperty("first_name") String firstName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(@JsonProperty("message_id") long messageId, Chat chat, User from, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallbackQuery(String id, User from, Message message, String data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(@JsonProperty("update_id") long updateId, Message message,
                         @JsonProperty("callback_query") CallbackQuery callbackQuery) {}
}