package com.imdemo.im.repo;

import com.imdemo.im.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByLogin(String login);

    boolean existsByLogin(String login);

    List<AppUser> findByPasswordHashIsNull();
}