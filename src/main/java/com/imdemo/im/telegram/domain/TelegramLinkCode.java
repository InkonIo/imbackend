package com.imdemo.im.telegram.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "telegram_link_code")
@Getter
@Setter
@NoArgsConstructor
public class TelegramLinkCode {
    @Id
    private String code;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}