package com.imdemo.im.service;

import com.imdemo.im.domain.ShiftSession;
import com.imdemo.im.repo.ShiftSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ShiftSessionRepository shifts;
    private final ActivityStore store;
    private final FlagService flags;

    /** Сигнал «я тут» с открытой вкладки. Не чаще раза в минуту имеет смысл. */
    @Transactional
    public void ping(Long userId) {
        ShiftSession s = shifts.findFirstByUserIdAndFinishedAtIsNull(userId).orElse(null);
        if (s == null) return;
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime last = store.lastMinute(s.getId());
        if (store.record(userId, s.getId(), AuditService.currentDeviceId())) {
            flags.onIdleGap(s, last != null ? last : s.getStartedAt(), now);
            flags.checkDevice(s);
        }
    }
}