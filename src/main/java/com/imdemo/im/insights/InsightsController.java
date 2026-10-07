package com.imdemo.im.insights;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Аналитика для SUPER_ADMIN и DIRECTOR. Права режутся в SecurityConfig (/api/insights/**) и в InsightsService.scope(). */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsService svc;

    public InsightsController(InsightsService svc) {
        this.svc = svc;
    }

    private static final String ISO = "yyyy-MM-dd";

    /** Точки, доступные текущему пользователю (для фильтра). */
    @GetMapping("/outlets")
    public List<Map<String, Object>> outlets(Authentication a) {
        return svc.outlets(svc.scope(a));
    }

    /** Общая картина: итоги, график по дням, точки, зоны, флаги, проблемные пункты, топ/антитоп, тепловая карта. */
    @GetMapping("/overview")
    public Map<String, Object> overview(Authentication a,
                                        @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                        @RequestParam(required = false) Long outletId) {
        return svc.overview(svc.scope(a), from, to, outletId);
    }

    /** Таблица менеджеров: KPI, рейтинг, разбор баллов (score_parts), средние по команде. */
    @GetMapping("/managers")
    public Map<String, Object> managers(Authentication a,
                                        @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                        @RequestParam(required = false) Long outletId) {
        return svc.managers(svc.scope(a), from, to, outletId);
    }

    /** Полная карточка менеджера. */
    @GetMapping("/managers/{id}")
    public Map<String, Object> manager(Authentication a, @PathVariable long id,
                                       @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                       @RequestParam(required = false) Long outletId) {
        return svc.manager(svc.scope(a), id, from, to, outletId);
    }

    /** История смен (uid необязателен: без него — все менеджеры). */
    @GetMapping("/shifts")
    public Map<String, Object> shifts(Authentication a,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                      @RequestParam(required = false) Long outletId,
                                      @RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        return svc.shifts(svc.scope(a), userId, from, to, outletId, limit, offset);
    }

    /** Одна смена в деталях: каждый пункт со временем и фото, флаги, проверки директора, события, активность, инвентаризация. */
    @GetMapping("/shifts/{id}")
    public Map<String, Object> shift(Authentication a, @PathVariable long id) {
        return svc.shift(svc.scope(a), id);
    }

    /** Журнал действий (входы, отметки, фото и т.д.). type — фильтр по типу события. */
    @GetMapping("/events")
    public Map<String, Object> events(Authentication a,
                                      @RequestParam(required = false) Long userId,
                                      @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                      @RequestParam(required = false) Long outletId,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        return svc.events(svc.scope(a), userId, from, to, outletId, type, limit, offset);
    }

    /** Инвентаризации: кто, когда, сколько строк заполнил, аномалии. */
    @GetMapping("/inventory/counts")
    public Map<String, Object> inventoryCounts(Authentication a,
                                               @RequestParam(required = false) Long userId,
                                               @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                               @RequestParam(required = false) Long outletId,
                                               @RequestParam(defaultValue = "100") int limit) {
        return svc.inventoryCounts(svc.scope(a), userId, from, to, outletId, limit);
    }

    /** Одна инвентаризация построчно: по каждому товару ящики/рукава/штуки/итого, прошлый раз, разница. */
    @GetMapping("/inventory/counts/{id}")
    public Map<String, Object> inventoryCount(Authentication a, @PathVariable long id) {
        return svc.inventoryCount(svc.scope(a), id);
    }

    /** Сводка по товарам: среднее, мин/макс, разброс, сколько аномалий. */
    @GetMapping("/inventory/products")
    public Map<String, Object> inventoryProducts(Authentication a,
                                                 @RequestParam(required = false) Long userId,
                                                 @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                                 @RequestParam(required = false) Long outletId) {
        return svc.inventoryProducts(svc.scope(a), userId, from, to, outletId);
    }

    /** История одного товара: кто и сколько насчитал в каждый день. */
    @GetMapping("/inventory/products/{productId}")
    public Map<String, Object> inventoryProduct(Authentication a, @PathVariable long productId,
                                                @RequestParam(required = false) Long userId,
                                                @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                                @RequestParam(required = false) Long outletId,
                                                @RequestParam(defaultValue = "200") int limit) {
        return svc.inventoryProductHistory(svc.scope(a), productId, userId, from, to, outletId, limit);
    }

    /** Сравнение 2–6 менеджеров: KPI рядом, динамика по неделям, пункты где они расходятся сильнее всего. */
    @GetMapping("/compare")
    public Map<String, Object> compare(Authentication a, @RequestParam List<Long> ids,
                                       @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                       @RequestParam(required = false) Long outletId) {
        return svc.compare(svc.scope(a), ids, from, to, outletId);
    }

    /** План vs факт по графику: вышел / не вышел, промежуточные, выходы вне графика, больничные и отпуска. */
    @GetMapping("/attendance")
    public Map<String, Object> attendance(Authentication a,
                                          @RequestParam(required = false) Long userId,
                                          @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                          @RequestParam(required = false) Long outletId) {
        return svc.attendance(svc.scope(a), userId, from, to, outletId);
    }

    /** Какие пункты чек-листа чаще всего пропускают, не выполняют или отмечают проблемой. */
    @GetMapping("/problem-items")
    public Map<String, Object> problemItems(Authentication a,
                                            @RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                            @RequestParam(required = false) Long outletId,
                                            @RequestParam(defaultValue = "50") int limit) {
        return svc.problemItems(svc.scope(a), userId, from, to, outletId, limit);
    }
}