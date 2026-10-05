package com.imdemo.im.telegram.controller;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.telegram.service.TelegramLinkService;
import com.imdemo.im.telegram.service.TelegramLinkService.Link;
import com.imdemo.im.telegram.service.TelegramLinkService.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/telegram")
@RequiredArgsConstructor
public class TelegramController {

    private final TelegramLinkService linking;

    @GetMapping("/status")
    public Status status(@AuthenticationPrincipal UserPrincipal p) {
        return linking.status(p.id());
    }

    @PostMapping("/link")
    public Link link(@AuthenticationPrincipal UserPrincipal p) {
        return linking.createLink(p.id());
    }

    @DeleteMapping("/link")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal UserPrincipal p) {
        linking.unlink(p.id());
        return ResponseEntity.noContent().build();
    }
}