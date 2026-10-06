package com.imdemo.im.template.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.repo.ChecklistTemplateRepository;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.AuditService;
import com.imdemo.im.service.ShiftClock;
import com.imdemo.im.template.dto.TemplateDto.*;
import com.imdemo.im.template.repository.ChecklistItemRepository;
import com.imdemo.im.template.repository.ChecklistSectionRepository;
import com.imdemo.im.web.error.ApiException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private final ChecklistTemplateRepository templates;
    private final ChecklistSectionRepository sections;
    private final ChecklistItemRepository items;
    private final OutletRepository outlets;
    private final UserRepository users;
    private final AuditService audit;
    private final EntityManager em;

    /** Суперадмин: всё. Директор: видит общие и свои, правит только маршруты своих точек. */
    private record Access(boolean admin, Set<Long> outletIds) {
        boolean canView(ChecklistTemplate t) {
            return admin || t.getOutletId() == null || outletIds.contains(t.getOutletId());
        }

        boolean canEdit(ChecklistTemplate t) {
            return admin || (t.getOutletId() != null && outletIds.contains(t.getOutletId()));
        }

        boolean canUseOutlet(Long outletId) {
            return admin || (outletId != null && outletIds.contains(outletId));
        }
    }

    // ================= маршруты =================

    @Transactional(readOnly = true)
    public List<Summary> list(UserPrincipal p) {
        Access a = access(p);
        Map<Long, String> names = outletNames();
        return templates.findAll(Sort.by("shiftRole", "dayPart", "id")).stream()
                .filter(a::canView)
                .map(t -> summary(t, a, names))
                .toList();
    }

    @Transactional(readOnly = true)
    public Full get(UserPrincipal p, Long id) {
        Access a = access(p);
        ChecklistTemplate t = template(id);
        if (!a.canView(t)) throw forbidden();
        return full(t, a);
    }

    @Transactional
    public Full create(UserPrincipal p, CreateRequest r) {
        Access a = access(p);
        Long outletId = r.outletId();
        if (outletId == null && !a.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Директор создаёт маршрут только для своей точки");
        }
        if (r.dayPart() == DayPart.MIDDLE && r.shiftRole() == ShiftRole.INSIDE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Промеж бывает только у кухни и прилавка");
        }
        if (outletId != null) {
            if (!a.canUseOutlet(outletId)) throw forbidden();
            if (!outlets.existsById(outletId)) throw new ApiException(HttpStatus.NOT_FOUND, "Точка не найдена");
        }
        boolean exists = outletId == null
                ? templates.existsByShiftRoleAndDayPartAndOutletIdIsNull(r.shiftRole(), r.dayPart())
                : templates.existsByShiftRoleAndDayPartAndOutletId(r.shiftRole(), r.dayPart(), outletId);
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "Такой маршрут уже есть. Открой его и редактируй");
        }

        ChecklistTemplate t = new ChecklistTemplate();
        t.setShiftRole(r.shiftRole());
        t.setDayPart(r.dayPart());
        t.setOutletId(outletId);
        t.setTitle(r.title().trim());
        t.setActive(true);

        if (r.copyFromId() != null) {
            ChecklistTemplate src = template(r.copyFromId());
            if (!a.canView(src)) throw forbidden();
            for (ChecklistSection ss : src.getSections()) {
                ChecklistSection ns = new ChecklistSection();
                ns.setTemplate(t);
                ns.setTitle(ss.getTitle());
                ns.setSortOrder(ss.getSortOrder());
                for (ChecklistItem si : ss.getItems()) {
                    ChecklistItem ni = copyItem(si);
                    ni.setSection(ns);
                    ns.getItems().add(ni);
                }
                t.getSections().add(ns);
            }
        }

        templates.saveAndFlush(t);
        log(t.getId(), "Маршрут создан: " + t.getTitle()
                + (r.copyFromId() != null ? " (копия маршрута #" + r.copyFromId() + ")" : ""));
        return reload(a, t.getId());
    }

    @Transactional
    public Full update(UserPrincipal p, Long id, UpdateRequest r) {
        Access a = access(p);
        ChecklistTemplate t = editable(a, template(id));
        t.setTitle(r.title().trim());
        if (r.active() != null) t.setActive(r.active());
        log(id, "Маршрут изменён: " + t.getTitle() + (t.isActive() ? "" : " (выключен)"));
        return reload(a, id);
    }

    @Transactional
    public void delete(UserPrincipal p, Long id) {
        Access a = access(p);
        ChecklistTemplate t = editable(a, template(id));
        log(id, "Маршрут удалён: " + t.getTitle());
        templates.delete(t);
        try {
            templates.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "По этому маршруту уже были смены. Удалить нельзя, выключи его");
        }
    }

    // ================= разделы =================

    @Transactional
    public Full addSection(UserPrincipal p, Long templateId, SectionRequest r) {
        Access a = access(p);
        ChecklistTemplate t = editable(a, template(templateId));
        ChecklistSection s = new ChecklistSection();
        s.setTemplate(t);
        s.setTitle(r.title().trim());
        s.setSortOrder(t.getSections().stream().mapToInt(ChecklistSection::getSortOrder).max().orElse(0) + 1);
        t.getSections().add(s);
        log(templateId, "Раздел добавлен: " + s.getTitle());
        return reload(a, templateId);
    }

    @Transactional
    public Full renameSection(UserPrincipal p, Long sectionId, SectionRequest r) {
        Access a = access(p);
        ChecklistSection s = section(sectionId);
        ChecklistTemplate t = editable(a, s.getTemplate());
        String old = s.getTitle();
        s.setTitle(r.title().trim());
        log(t.getId(), "Раздел: " + old + " → " + s.getTitle());
        return reload(a, t.getId());
    }

    @Transactional
    public Full deleteSection(UserPrincipal p, Long sectionId) {
        Access a = access(p);
        ChecklistSection s = section(sectionId);
        ChecklistTemplate t = editable(a, s.getTemplate());
        log(t.getId(), "Раздел удалён: " + s.getTitle() + " (пунктов: " + s.getItems().size() + ")");
        t.getSections().remove(s); // orphanRemoval удалит раздел и его пункты
        return reload(a, t.getId());
    }

    @Transactional
    public Full orderSections(UserPrincipal p, Long templateId, OrderRequest r) {
        Access a = access(p);
        ChecklistTemplate t = editable(a, template(templateId));
        reorder(t.getSections(), r.ids(), ChecklistSection::getId, ChecklistSection::setSortOrder);
        log(templateId, "Изменён порядок разделов");
        return reload(a, templateId);
    }

    // ================= пункты =================

    @Transactional
    public Full addItem(UserPrincipal p, Long sectionId, ItemRequest r) {
        Access a = access(p);
        ChecklistSection s = section(sectionId);
        ChecklistTemplate t = editable(a, s.getTemplate());
        validate(r);
        ChecklistItem i = new ChecklistItem();
        apply(i, r);
        i.setSection(s);
        i.setSortOrder(nextItemOrder(s));
        s.getItems().add(i);
        log(t.getId(), "Пункт добавлен: " + i.getTitle() + " (раздел «" + s.getTitle() + "»)");
        return reload(a, t.getId());
    }

    @Transactional
    public Full updateItem(UserPrincipal p, Long itemId, ItemRequest r) {
        Access a = access(p);
        ChecklistItem i = item(itemId);
        ChecklistSection current = i.getSection();
        ChecklistTemplate t = editable(a, current.getTemplate());
        validate(r);
        apply(i, r);

        // перенос в другой раздел этого же маршрута
        if (r.sectionId() != null && !r.sectionId().equals(current.getId())) {
            ChecklistSection target = section(r.sectionId());
            if (!target.getTemplate().getId().equals(t.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Перенести можно только в раздел этого маршрута");
            }
            i.setSortOrder(nextItemOrder(target));
            i.setSection(target);
        }
        log(t.getId(), "Пункт изменён: " + i.getTitle());
        return reload(a, t.getId());
    }

    @Transactional
    public Full deleteItem(UserPrincipal p, Long itemId) {
        Access a = access(p);
        ChecklistItem i = item(itemId);
        ChecklistTemplate t = editable(a, i.getSection().getTemplate());
        Long templateId = t.getId();
        log(templateId, "Пункт удалён: " + i.getTitle());
        items.delete(i);
        return reload(a, templateId);
    }

    @Transactional
    public Full orderItems(UserPrincipal p, Long sectionId, OrderRequest r) {
        Access a = access(p);
        ChecklistSection s = section(sectionId);
        ChecklistTemplate t = editable(a, s.getTemplate());
        reorder(s.getItems(), r.ids(), ChecklistItem::getId, ChecklistItem::setSortOrder);
        log(t.getId(), "Изменён порядок пунктов в разделе «" + s.getTitle() + "»");
        return reload(a, t.getId());
    }

    // ================= внутреннее =================

    private Access access(UserPrincipal p) {
        if (p.role() == AccountRole.SUPER_ADMIN) return new Access(true, Set.of());
        Set<Long> ids = users.findById(p.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
        return new Access(false, ids);
    }

    private ChecklistTemplate editable(Access a, ChecklistTemplate t) {
        if (!a.canEdit(t)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    t.getOutletId() == null
                            ? "Общий маршрут правит только суперадмин. Создай копию для своей точки"
                            : "Нет доступа к этой точке");
        }
        return t;
    }

    /** Сохранить, сбросить кэш и перечитать маршрут в правильном порядке. */
    private Full reload(Access a, Long templateId) {
        em.flush();
        em.clear();
        return full(template(templateId), a);
    }

    private Full full(ChecklistTemplate t, Access a) {
        List<Section> secs = t.getSections().stream()
                .map(s -> new Section(s.getId(), s.getTitle(), s.getSortOrder(),
                        s.getItems().stream().map(i -> item(s, i)).toList()))
                .toList();
        return new Full(summary(t, a, outletNames()), secs);
    }

    private Summary summary(ChecklistTemplate t, Access a, Map<Long, String> names) {
        int itemCount = t.getSections().stream().mapToInt(s -> s.getItems().size()).sum();
        return new Summary(t.getId(), t.getShiftRole(), t.getDayPart(), t.getOutletId(),
                t.getOutletId() == null ? null : names.get(t.getOutletId()),
                t.getTitle(), t.isActive(), t.getSections().size(), itemCount, a.canEdit(t));
    }

        private static Item item(ChecklistSection s, ChecklistItem i) {
        return new Item(i.getId(), s.getId(), i.getTitle(), i.getInstructions(), i.getSortOrder(),
                i.getDurationMin(), i.getDueFrom(), i.getDueTo(), i.getPhotoMode(),
                i.getWeekday(), i.isDirectorReview(), i.isTelegramNotify(), i.isActive());
    }

    private static ChecklistItem copyItem(ChecklistItem si) {
        ChecklistItem ni = new ChecklistItem();
        ni.setTelegramNotify(si.isTelegramNotify());
        ni.setTitle(si.getTitle());
        ni.setInstructions(si.getInstructions());
        ni.setSortOrder(si.getSortOrder());
        ni.setDurationMin(si.getDurationMin());
        ni.setDueFrom(si.getDueFrom());
        ni.setDueTo(si.getDueTo());
        ni.setPhotoMode(si.getPhotoMode());
        ni.setWeekday(si.getWeekday());
        ni.setDirectorReview(si.isDirectorReview());
        ni.setActive(si.isActive());
        return ni;
    }

    private static void apply(ChecklistItem i, ItemRequest r) {
        i.setTitle(r.title().trim());
        i.setInstructions(r.instructions() == null || r.instructions().isBlank() ? null : r.instructions().trim());
        i.setTelegramNotify(r.telegramNotify());
        i.setDurationMin(r.durationMin());
        i.setDueFrom(r.dueFrom());
        i.setDueTo(r.dueTo());
        i.setPhotoMode(r.photoMode());
        i.setWeekday(r.weekday());
        i.setDirectorReview(r.directorReview());
        i.setActive(r.active() == null || r.active());
    }

    private static void validate(ItemRequest r) {
        if (r.dueFrom() != null && r.dueTo() != null
                && ShiftClock.order(r.dueFrom()) >= ShiftClock.order(r.dueTo())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Время «с» должно быть раньше «до»");
        }
    }

    private static int nextItemOrder(ChecklistSection s) {
        return s.getItems().stream().mapToInt(ChecklistItem::getSortOrder).max().orElse(0) + 1;
    }

    private static <T> void reorder(List<T> list, List<Long> ids, Function<T, Long> idOf, BiConsumer<T, Integer> set) {
        Set<Long> have = list.stream().map(idOf).collect(Collectors.toSet());
        if (ids.size() != have.size() || !have.equals(new HashSet<>(ids))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Список устарел, обнови страницу");
        }
        Map<Long, T> byId = list.stream().collect(Collectors.toMap(idOf, Function.identity()));
        for (int k = 0; k < ids.size(); k++) set.accept(byId.get(ids.get(k)), k + 1);
    }

    private Map<Long, String> outletNames() {
        return outlets.findAll().stream().collect(Collectors.toMap(Outlet::getId, Outlet::getName));
    }

    private ChecklistTemplate template(Long id) {
        return templates.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Маршрут не найден"));
    }

    private ChecklistSection section(Long id) {
        return sections.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Раздел не найден"));
    }

    private ChecklistItem item(Long id) {
        return items.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пункт не найден"));
    }

    private void log(Long templateId, String details) {
        audit.log(AuditEventType.TEMPLATE_CHANGED, "template", templateId, details);
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "Нет доступа");
    }
}