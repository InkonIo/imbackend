package com.imdemo.im.service;

import com.imdemo.im.domain.AppUser;
import com.imdemo.im.domain.AuditEventType;
import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.dto.Mappers;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.security.JwtService;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;

    @Transactional
    public LoginResponse login(LoginRequest r) {
        String login = r.login().trim();
        AppUser u = userRepository.findByLogin(login).orElse(null);

        String failReason = null;
        if (u == null) failReason = "нет такого логина";
        else if (!u.isActive()) failReason = "аккаунт отключён";
        else if (u.getPasswordHash() == null || !passwordEncoder.matches(r.password(), u.getPasswordHash()))
            failReason = "неверный пароль";

        if (failReason != null) {
            audit.logFailedLogin(login, u == null ? null : u.getId(), failReason);
            throw badCredentials(); // клиенту причину не раскрываем
        }

        audit.logAs(u, AuditEventType.LOGIN, null);
        return new LoginResponse(jwtService.generate(u), Mappers.user(u));
    }

    @Transactional(readOnly = true)
    public UserDto me(Long userId) {
        return Mappers.user(find(userId));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest r) {
        AppUser u = find(userId);
        if (u.getPasswordHash() == null || !passwordEncoder.matches(r.oldPassword(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Старый пароль неверный");
        }
        u.setPasswordHash(passwordEncoder.encode(r.newPassword()));
        u.setMustChangePassword(false);
        audit.logAs(u, AuditEventType.PASSWORD_CHANGED, null);
    }

    @Transactional
    public void logout(Long userId) {
        audit.log(AuditEventType.LOGOUT, "user", userId, null);
    }

    private AppUser find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
    }

    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
    }
}