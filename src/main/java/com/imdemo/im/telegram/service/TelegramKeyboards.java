package com.imdemo.im.telegram.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.imdemo.im.telegram.client.TelegramClient.button;
import static com.imdemo.im.telegram.client.TelegramClient.keyboard;

/**
 * Кнопки под сообщениями. callback_data до 64 байт:
 * ia:ID / ir:ID — пункт принял / не принял; fc:ID / fd:ID — флаг: нарушение / всё ок;
 * rs:K:ID:N — выбрана причина N; rw:K:ID — своя причина; rx:K:ID — отмена. K = i (пункт) или f (флаг).
 */
public final class TelegramKeyboards {
    private TelegramKeyboards() {}

    public static final List<String> REASONS = List.of(
            "Работа не выполнена",
            "Фото не подтверждает выполнение",
            "Прокликано слишком быстро",
            "Не по стандарту",
            "Старое или чужое фото");

    public static Map<String, Object> decision(char kind, long id) {
        return kind == 'i'
                ? keyboard(List.of(List.of(button("✅ Принял", "ia:" + id), button("❌ Не принял", "ir:" + id))))
                : keyboard(List.of(List.of(button("🚩 Нарушение", "fc:" + id), button("👌 Всё ок", "fd:" + id))));
    }

    public static Map<String, Object> reasons(char kind, long id) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (int n = 0; n < REASONS.size(); n++) {
            rows.add(List.of(button(REASONS.get(n), "rs:" + kind + ":" + id + ":" + n)));
        }
        rows.add(List.of(button("✍️ Свой текст", "rw:" + kind + ":" + id), button("↩ Отмена", "rx:" + kind + ":" + id)));
        return keyboard(rows);
    }
}