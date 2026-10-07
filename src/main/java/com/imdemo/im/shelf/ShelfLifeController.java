package com.imdemo.im.shelf;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * GET /api/shelf-life — для любой роли (менеджер, инсайд, директор, супер-админ), нужен только вход.
 * Всё под /api/shelf-life/admin/** — только SUPER_ADMIN и DIRECTOR (правило в SecurityConfig).
 */
@RestController
@RequestMapping("/api/shelf-life")
public class ShelfLifeController {

    private final ShelfLifeService svc;

    public ShelfLifeController(ShelfLifeService svc) {
        this.svc = svc;
    }

    /** q — поиск по словам; sectionId и zone (ROOM/COLD/FREEZER/WATER) — необязательные фильтры. */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Long sectionId,
                                    @RequestParam(required = false) String zone) {
        return svc.list(q, sectionId, zone);
    }

    @PutMapping("/admin/doc")
    public Map<String, Object> doc(@RequestBody ShelfLifeService.DocBody b) {
        svc.updateDoc(b);
        return Map.of("ok", true);
    }

    @PostMapping("/admin/products")
    public Map<String, Object> createProduct(@RequestBody ShelfLifeService.ProductBody b) {
        return Map.of("id", svc.createProduct(b));
    }

    @PutMapping("/admin/products/{id}")
    public Map<String, Object> updateProduct(@PathVariable long id, @RequestBody ShelfLifeService.ProductBody b) {
        svc.updateProduct(id, b);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/products/{id}")
    public Map<String, Object> deleteProduct(@PathVariable long id) {
        svc.deleteProduct(id);
        return Map.of("ok", true);
    }

    @PostMapping("/admin/products/{id}/rules")
    public Map<String, Object> createRule(@PathVariable long id, @RequestBody ShelfLifeService.RuleBody b) {
        return Map.of("id", svc.createRule(id, b));
    }

    @PutMapping("/admin/rules/{id}")
    public Map<String, Object> updateRule(@PathVariable long id, @RequestBody ShelfLifeService.RuleBody b) {
        svc.updateRule(id, b);
        return Map.of("ok", true);
    }

    @DeleteMapping("/admin/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable long id) {
        svc.deleteRule(id);
        return Map.of("ok", true);
    }
}