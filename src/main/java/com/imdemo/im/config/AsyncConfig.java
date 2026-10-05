package com.imdemo.im.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Фоновая отправка в Telegram и расписание (итоги недели). */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}