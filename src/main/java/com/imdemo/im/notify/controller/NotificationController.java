package com.imdemo.im.notify.controller;

import com.imdemo.im.notify.dto.NotificationDto;
import com.imdemo.im.notify.service.NotificationService;
import com.imdemo.im.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public NotificationDto.ListDto list(@AuthenticationPrincipal UserPrincipal p) {
        return service.list(p.id());
    }

    @GetMapping("/unread")
    public NotificationDto.Unread unread(@AuthenticationPrincipal UserPrincipal p) {
        return service.unread(p.id());
    }

    @PostMapping("/read-all")
    public NotificationDto.Unread readAll(@AuthenticationPrincipal UserPrincipal p) {
        return service.readAll(p.id());
    }
}