package com.imdemo.im.review.controller;

import com.imdemo.im.review.dto.ReviewDto.*;
import com.imdemo.im.review.service.ReviewService;
import com.imdemo.im.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService service;

    @GetMapping("/queue")
    public QueueDto queue(@AuthenticationPrincipal UserPrincipal p) {
        return service.queue(p);
    }

    @GetMapping("/summary")
    public SummaryDto summary(@AuthenticationPrincipal UserPrincipal p) {
        return service.summary(p);
    }

    @PostMapping("/flags/{id}")
    public SummaryDto decideFlag(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                 @Valid @RequestBody FlagDecisionRequest r) {
        return service.decideFlag(p, id, r);
    }

    @PostMapping("/shifts/{id}/flags")
    public SummaryDto decideShift(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                  @Valid @RequestBody FlagDecisionRequest r) {
        return service.decideShift(p, id, r);
    }

    @PostMapping("/items/{id}")
    public SummaryDto decideItem(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                 @Valid @RequestBody ItemDecisionRequest r) {
        return service.decideItem(p, id, r);
    }
}