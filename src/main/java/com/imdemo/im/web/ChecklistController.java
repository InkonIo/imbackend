package com.imdemo.im.web;

import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.ChecklistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

@RestController
@RequestMapping("/api/checklist")
@RequiredArgsConstructor
public class ChecklistController {

    private final ChecklistService service;

    @GetMapping("/current")
    public ResponseEntity<ChecklistDto> current(@AuthenticationPrincipal UserPrincipal p) {
        return service.current(p.id()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/items/{id}")
    public ChecklistDto update(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                               @Valid @RequestBody UpdateRunItemRequest r) {
        return service.update(p.id(), id, r);
    }

        @PostMapping(value = "/items/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChecklistDto addPhoto(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "takenAt", required = false) Long takenAt) {
        return service.addPhoto(p.id(), id, file, takenAt);
    }

    @DeleteMapping("/photos/{id}")
    public ChecklistDto deletePhoto(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.deletePhoto(p.id(), id);
    }

    @GetMapping("/photos/{id}")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        ChecklistService.PhotoFile f = service.photo(p, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(new FileSystemResource(f.path()));
    }

    @PostMapping("/items/{id}/start")
    public ChecklistDto start(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.start(p.id(), id);
    }
}