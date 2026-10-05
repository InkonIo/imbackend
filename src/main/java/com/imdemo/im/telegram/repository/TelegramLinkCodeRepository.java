package com.imdemo.im.telegram.repository;

import com.imdemo.im.telegram.domain.TelegramLinkCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface TelegramLinkCodeRepository extends JpaRepository<TelegramLinkCode, String> {

    @Modifying
    @Query("delete from TelegramLinkCode c where c.userId = :userId or c.expiresAt < :now")
    int deleteForUserOrExpired(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
}