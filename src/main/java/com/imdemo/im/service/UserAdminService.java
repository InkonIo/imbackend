package com.imdemo.im.service;

import com.imdemo.im.domain.AppUser;
import com.imdemo.im.domain.AuditEventType;
import com.imdemo.im.domain.Outlet;
import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.dto.Mappers;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository users;
    private final OutletRepository outlets;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<UserDto> list() {
        return users.findAll(Sort.by("id")).stream().map(Mappers::user).toList();
    }

    @Transactional(readOnly = true)
    public UserDto get(Long id) {
        return Mappers.user(find(id));
    }

    @Transactional
    public UserWithPassword create(CreateUserRequest r) {
        String login = r.login().trim();
        if (users.existsByLogin(login)) {
            throw new ApiException(HttpStatus.CONFLICT, "Логин уже занят");
        }
        String raw = PasswordGenerator.generate(10);
        AppUser u = new AppUser();
        u.setLogin(login);
        u.setFullName(r.fullName().trim());
        u.setAccountRole(r.accountRole());
        u.setPasswordHash(passwordEncoder.encode(raw));
        u.setMustChangePassword(true);
        u.setOutlets(findOutlets(r.outletIds()));
        AppUser saved = users.saveAndFlush(u);
        audit.log(AuditEventType.USER_CREATED, "user", saved.getId(),
                saved.getLogin() + " · " + saved.getAccountRole() + " · точек: " + saved.getOutlets().size());
        return new UserWithPassword(Mappers.user(saved), raw);
    }

    @Transactional
    public UserDto update(Long id, Long currentUserId, UpdateUserRequest r) {
        AppUser u = find(id);
        List<String> changes = new ArrayList<>();

        if (r.fullName() != null && !r.fullName().isBlank() && !r.fullName().trim().equals(u.getFullName())) {
            changes.add("имя: " + u.getFullName() + " → " + r.fullName().trim());
            u.setFullName(r.fullName().trim());
        }
        if (r.accountRole() != null && r.accountRole() != u.getAccountRole()) {
            if (id.equals(currentUserId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Нельзя менять свою роль");
            }
            changes.add("роль: " + u.getAccountRole() + " → " + r.accountRole());
            u.setAccountRole(r.accountRole());
        }
        if (r.active() != null && r.active() != u.isActive()) {
            if (id.equals(currentUserId) && !r.active()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Нельзя отключить себя");
            }
            changes.add(r.active() ? "включён" : "отключён");
            u.setActive(r.active());
        }
        if (r.outletIds() != null) {
            Set<Outlet> next = findOutlets(r.outletIds());
            if (!next.equals(u.getOutlets())) {
                changes.add("точек: " + u.getOutlets().size() + " → " + next.size());
                u.setOutlets(next);
            }
        }

        AppUser saved = users.saveAndFlush(u);
        if (!changes.isEmpty()) {
            audit.log(AuditEventType.USER_UPDATED, "user", saved.getId(),
                    saved.getLogin() + " · " + String.join("; ", changes));
        }
        return Mappers.user(saved);
    }

    @Transactional
    public UserWithPassword resetPassword(Long id) {
        AppUser u = find(id);
        String raw = PasswordGenerator.generate(10);
        u.setPasswordHash(passwordEncoder.encode(raw));
        u.setMustChangePassword(true);
        audit.log(AuditEventType.USER_PASSWORD_RESET, "user", u.getId(), u.getLogin());
        return new UserWithPassword(Mappers.user(u), raw);
    }

    @Transactional
    public void delete(Long id, Long currentUserId) {
        if (id.equals(currentUserId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Нельзя удалить себя");
        }
        AppUser u = find(id);
        audit.log(AuditEventType.USER_DELETED, "user", u.getId(), u.getLogin() + " · " + u.getFullName());
        users.delete(u);
        users.flush();
    }

    private AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    private Set<Outlet> findOutlets(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashSet<>();
        }
        List<Outlet> found = outlets.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Какая-то из точек не найдена");
        }
        return new HashSet<>(found);
    }
}