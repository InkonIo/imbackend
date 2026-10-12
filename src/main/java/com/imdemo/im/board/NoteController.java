package com.imdemo.im.board;

import com.imdemo.im.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outlet-notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService service;

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal UserPrincipal p,
                                          @RequestParam(required = false) Long outletId,
                                          @RequestParam(defaultValue = "false") boolean all) {
        return service.list(p, outletId, all);
    }

    @PostMapping
    public Map<String, Object> create(@AuthenticationPrincipal UserPrincipal p, @RequestBody NoteService.In in) {
        return service.create(p, in);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                      @RequestBody NoteService.In in) {
        return service.update(p, id, in);
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> status(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id,
                                      @RequestBody NoteService.StatusIn in) {
        return service.setStatus(p, id, in);
    }

    @GetMapping("/{id}/log")
    public List<Map<String, Object>> log(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.log(p, id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@AuthenticationPrincipal UserPrincipal p, @PathVariable Long id) {
        return service.delete(p, id);
    }
}