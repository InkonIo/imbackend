package com.imdemo.im.analytics.controller;

import com.imdemo.im.analytics.dto.RatingDto.Board;
import com.imdemo.im.analytics.service.RatingService;
import com.imdemo.im.domain.ShiftRole;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/rating")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService rating;

    @GetMapping
    public Board board(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "INSIDE") ShiftRole role,
                       @RequestParam(required = false) Long outletId) {
        return rating.board(from, to, role, outletId);
    }
}