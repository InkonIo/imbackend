package com.imdemo.im.security;

import com.imdemo.im.domain.AccountRole;

public record UserPrincipal(Long id, String login, AccountRole role) {}