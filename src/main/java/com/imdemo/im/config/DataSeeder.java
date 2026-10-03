package com.imdemo.im.config;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.AppUser;
import com.imdemo.im.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.admin-login:}")
    private String adminLogin;

    @Value("${app.seed.admin-password:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!adminLogin.isBlank() && !adminPassword.isBlank() && !userRepository.existsByLogin(adminLogin)) {
            AppUser admin = new AppUser();
            admin.setLogin(adminLogin);
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setFullName("Super Admin");
            admin.setAccountRole(AccountRole.SUPER_ADMIN);
            userRepository.save(admin);
            log.info("Создан суперадмин: {}", adminLogin);
        }

        for (AppUser user : userRepository.findByPasswordHashIsNull()) {
            String rawPassword = randomPassword(10);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setMustChangePassword(true);
            log.info("=== ПАРОЛЬ id={} логин={} пароль={} (показан один раз) ===",
                    user.getId(), user.getLogin(), rawPassword);
        }
    }

    private static String randomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}