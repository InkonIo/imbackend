package com.imdemo.im.telegram.repository;

import com.imdemo.im.telegram.domain.TelegramLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TelegramLinkRepository extends JpaRepository<TelegramLink, Long> {
    Optional<TelegramLink> findByUserId(Long userId);

    Optional<TelegramLink> findByChatId(Long chatId);

    List<TelegramLink> findByUserIdIn(Collection<Long> userIds);
}