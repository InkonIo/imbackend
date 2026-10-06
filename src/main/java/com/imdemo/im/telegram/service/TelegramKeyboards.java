package com.imdemo.im.telegram.service;

import com.imdemo.im.domain.AuditFlag;
import com.imdemo.im.domain.FlagType;

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

    public static final Map<FlagType, String> FLAG_LABEL = Map.ofEntries(
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

    public static String label(FlagType t) {
        return FLAG_LABEL.getOrDefault(t, t.name());
    }

    /** Одиночное решение (флаг без пункта). */
    public static Map<String, Object> decision(char kind, long id) {
        return kind == 'i'
                ? keyboard(List.of(List.of(button("✅ Принял", "ia:" + id), button("❌ Не принял", "ir:" + id))))
                : keyboard(List.of(List.of(button("🚩 Нарушение", "fc:" + id), button("👌 Всё ок", "fd:" + id))));
    }

    /**
     * Общая клавиатура пункта: строка проверки (если ждёт решения) + строка на каждый открытый флаг.
     * null — решать больше нечего, кнопки убрать.
     */
    public static Map<String, Object> combined(Long reviewItemId, List<AuditFlag> openFlags) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        if (reviewItemId != null) {
            rows.add(List.of(button("✅ Принял", "ia:" + reviewItemId), button("❌ Не принял", "ir:" + reviewItemId)));
        }
        if (openFlags.size() == 1) {
            long id = openFlags.get(0).getId();
            rows.add(List.of(button("🚩 Нарушение", "fc:" + id), button("👌 Всё ок", "fd:" + id)));
        } else {
            for (AuditFlag f : openFlags) {
                rows.add(List.of(button("🚩 " + label(f.getType()), "fc:" + f.getId()), button("👌 ок", "fd:" + f.getId())));
            }
        }
        return rows.isEmpty() ? null : keyboard(rows);
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