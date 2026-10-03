package com.imdemo.im.template.controller;

import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.template.dto.TemplateDto.*;
import com.imdemo.im.template.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService service;

    // ---- маршруты ----
    @GetMapping
    public List<Summary> list(@AuthenticationPrincipal UserPrincipal p) {
        return service.list(p);
    }

    @GetMapping("/{id}")
    public Full get(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.get(p, id);
    }

    @PostMapping
    public Full create(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody CreateRequest r) {
        return service.create(p, r);
    }

    @PutMapping("/{id}")
    public Full update(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                       @Valid @RequestBody UpdateRequest r) {
        return service.update(p, id, r);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        service.delete(p, id);
        return ResponseEntity.noContent().build();
    }

    // ---- разделы ----
    @PostMapping("/{id}/sections")
    public Full addSection(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                           @Valid @RequestBody SectionRequest r) {
        return service.addSection(p, id, r);
    }

    @PutMapping("/{id}/sections/order")
    public Full orderSections(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                              @Valid @RequestBody OrderRequest r) {
        return service.orderSections(p, id, r);
    }

    @PutMapping("/sections/{sectionId}")
    public Full renameSection(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long sectionId,
                              @Valid @RequestBody SectionRequest r) {
        return service.renameSection(p, sectionId, r);
    }

    @DeleteMapping("/sections/{sectionId}")
    public Full deleteSection(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long sectionId) {
        return service.deleteSection(p, sectionId);
    }

    // ---- пункты ----
    @PostMapping("/sections/{sectionId}/items")
    public Full addItem(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long sectionId,
                        @Valid @RequestBody ItemRequest r) {
        return service.addItem(p, sectionId, r);
    }

    @PutMapping("/sections/{sectionId}/items/order")
    public Full orderItems(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long sectionId,
                           @Valid @RequestBody OrderRequest r) {
        return service.orderItems(p, sectionId, r);
    }

    @PutMapping("/items/{itemId}")
    public Full updateItem(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long itemId,
                           @Valid @RequestBody ItemRequest r) {
        return service.updateItem(p, itemId, r);
    }

    @DeleteMapping("/items/{itemId}")
    public Full deleteItem(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long itemId) {
        return service.deleteItem(p, itemId);
    }
}