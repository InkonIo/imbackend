package com.imdemo.im.telegram.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "telegram_link")
@Getter
@Setter
@NoArgsConstructor
public class TelegramLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "chat_id", nullable = false, unique = true)
    private Long chatId;

    private String username;

    @Column(name = "linked_at", nullable = false)
    private OffsetDateTime linkedAt = OffsetDateTime.now();
}