package com.imdemo.im.web;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.ActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activity;

    @PostMapping("/ping")
    public ResponseEntity<Void> ping(@AuthenticationPrincipal UserPrincipal p) {
        activity.ping(p.id());
        return ResponseEntity.noContent().build();
    }
}