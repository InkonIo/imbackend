package com.imdemo.im.analytics.controller;

import com.imdemo.im.analytics.dto.RatingDto.Board;
import com.imdemo.im.analytics.service.RatingService;
import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.ShiftRole;
import com.imdemo.im.insights.InsightsService;
import com.imdemo.im.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/rating")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService rating;
    private final InsightsService insights;

    /**
     * Супер-админ (РМ) видит рейтинг всех точек. Директор видит только свои точки (user_outlet):
     * чужой outletId даёт 403, а без outletId подставляется его единственная точка.
     */
    @GetMapping
    public Board board(Authentication auth,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "INSIDE") ShiftRole role,
                       @RequestParam(required = false) Long outletId) {
        Long outlet = outletId;
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p && p.role() == AccountRole.DIRECTOR) {
            List<Long> mine = insights.scope(auth).outlets();
            if (mine.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет точек");
            if (outlet != null && !mine.contains(outlet)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Нет доступа к этому ресторану");
            }
            if (outlet == null) {
                if (mine.size() > 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите ресторан");
                }
                outlet = mine.get(0);
            }
        }
        return rating.board(from, to, role, outlet);
    }
}