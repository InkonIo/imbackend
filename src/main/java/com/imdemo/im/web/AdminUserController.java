package com.imdemo.im.web;

import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.UserAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService service;

    @GetMapping
    public List<UserDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public UserDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    public UserWithPassword create(@Valid @RequestBody CreateUserRequest r) {
        return service.create(r);
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal p,
                          @Valid @RequestBody UpdateUserRequest r) {
        return service.update(id, p.id(), r);
    }

    @PostMapping("/{id}/reset-password")
    public UserWithPassword resetPassword(@PathVariable Long id) {
        return service.resetPassword(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal p) {
        service.delete(id, p.id());
        return ResponseEntity.noContent().build();
    }
}