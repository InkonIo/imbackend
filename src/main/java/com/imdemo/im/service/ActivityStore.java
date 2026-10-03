package com.imdemo.im.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Хранилище «минут активности». Простой JDBC, без сущности. */
@Component
@RequiredArgsConstructor
public class ActivityStore {

    private final JdbcTemplate jdbc;

    public OffsetDateTime lastMinute(Long shiftId) {
        return jdbc.queryForObject(
                "SELECT max(minute) FROM activity_minute WHERE shift_id = ?", OffsetDateTime.class, shiftId);
    }

    /** true, если это новая минута (в эту минуту пинга ещё не было). */
    public boolean record(Long userId, Long shiftId, String deviceId) {
        return jdbc.update("""
                INSERT INTO activity_minute (user_id, minute, shift_id, device_id)
                VALUES (?, date_trunc('minute', now()), ?, ?)
                ON CONFLICT DO NOTHING
                """, userId, shiftId, deviceId) > 0;
    }

    public Map<Long, Integer> activeMinutes(Collection<Long> shiftIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (shiftIds.isEmpty()) return result;
        String in = shiftIds.stream().map(id -> "?").collect(Collectors.joining(","));
        jdbc.query("SELECT shift_id, count(*) FROM activity_minute WHERE shift_id IN (" + in + ") GROUP BY shift_id",
                rs -> {
                    result.put(rs.getLong(1), rs.getInt(2));
                },
                shiftIds.toArray());
        return result;
    }
}