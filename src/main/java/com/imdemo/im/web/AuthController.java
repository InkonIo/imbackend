package com.imdemo.im.web;

import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest r) {
        return authService.login(r);
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal UserPrincipal p) {
        return authService.me(p.id());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal UserPrincipal p,
                                               @Valid @RequestBody ChangePasswordRequest r) {
        authService.changePassword(p.id(), r);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserPrincipal p) {
        authService.logout(p.id());
        return ResponseEntity.noContent().build();
    }
}