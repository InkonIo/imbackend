package com.imdemo.im.notify.service;

import com.imdemo.im.events.AppEvents;
import com.imdemo.im.notify.domain.Notification;
import com.imdemo.im.notify.domain.NotificationType;
import com.imdemo.im.notify.dto.NotificationDto;
import com.imdemo.im.notify.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repo;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public Notification send(Long userId, NotificationType type, String title, String body,
                             Long shiftId, Long runItemId, Long flagId, Long createdBy) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(cut(title, 200));
        n.setBody(cut(body, 1000));
        n.setShiftId(shiftId);
        n.setRunItemId(runItemId);
        n.setFlagId(flagId);
        n.setCreatedBy(createdBy);
        Notification saved = repo.save(n);
        publisher.publishEvent(new AppEvents.NotificationCreated(saved.getId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public NotificationDto.ListDto list(Long userId) {
        var items = repo.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(n -> new NotificationDto.Item(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                        n.getShiftId(), n.getRunItemId(), n.getFlagId(), n.getCreatedAt(), n.getReadAt()))
                .toList();
        return new NotificationDto.ListDto(items, repo.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional(readOnly = true)
    public NotificationDto.Unread unread(Long userId) {
        return new NotificationDto.Unread(repo.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional
    public NotificationDto.Unread readAll(Long userId) {
        repo.markAllRead(userId, OffsetDateTime.now());
        return new NotificationDto.Unread(0);
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}