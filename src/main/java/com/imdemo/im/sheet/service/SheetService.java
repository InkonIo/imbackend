package com.imdemo.im.sheet.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.sheet.domain.ShiftSheet;
import com.imdemo.im.sheet.domain.ShiftSheetValue;
import com.imdemo.im.sheet.dto.SheetDto.Saved;
import com.imdemo.im.sheet.dto.SheetDto.Sheet;
import com.imdemo.im.sheet.repository.ShiftSheetRepository;
import com.imdemo.im.sheet.repository.ShiftSheetValueRepository;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SheetService {

    private static final Pattern KEY = Pattern.compile("^[a-zA-Z0-9._-]{1,100}$");
    private static final int MAX_VALUE = 2000;

    private final ShiftSheetRepository sheets;
    private final ShiftSheetValueRepository values;
    private final ShiftSessionRepository shifts;
    private final OutletRepository outlets;
    private final UserRepository users;

    private final com.imdemo.im.schedule.repository.ScheduleSlotRepository scheduleSlots;

    /** Лист точки на дату моей открытой смены. Создаётся при первом открытии. */
    @Transactional
    public Sheet current(UserPrincipal p) {
        ShiftSession s = shifts.findFirstByUserIdAndFinishedAtIsNull(p.id())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Начни смену, чтобы открыть чек-лист"));
        Long outletId = s.getOutlet().getId();
        ShiftSheet sheet = sheets.findByOutletIdAndSheetDate(outletId, s.getShiftDate()).orElseGet(() -> {
            ShiftSheet n = new ShiftSheet();
            n.setOutletId(outletId);
            n.setSheetDate(s.getShiftDate());
            return sheets.saveAndFlush(n);
        });
        return dto(sheet, p);
    }

    @Transactional(readOnly = true)
    public Sheet get(UserPrincipal p, Long id) {
        ShiftSheet sheet = sheet(id);
        if (!canView(p, sheet)) throw forbidden();
        return dto(sheet, p);
    }

    @Transactional
    public Saved save(UserPrincipal p, Long id, Map<String, String> changes) {
        ShiftSheet sheet = sheet(id);
        if (!canEdit(p, sheet)) throw forbidden();

        for (Map.Entry<String, String> e : changes.entrySet()) {
            if (!KEY.matcher(e.getKey()).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректное поле: " + e.getKey());
            }
            if (e.getValue() != null && e.getValue().length() > MAX_VALUE) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Слишком длинный текст (до " + MAX_VALUE + " символов)");
            }
        }

        Map<String, ShiftSheetValue> existing = values.findBySheetIdAndFieldKeyIn(id, changes.keySet()).stream()
                .collect(Collectors.toMap(ShiftSheetValue::getFieldKey, v -> v));
        OffsetDateTime now = OffsetDateTime.now();

        changes.forEach((key, raw) -> {
            String value = raw == null || raw.isBlank() ? null : raw;
            ShiftSheetValue v = existing.get(key);
            if (value == null) {
                if (v != null) values.delete(v);
                return;
            }
            if (v == null) {
                v = new ShiftSheetValue();
                v.setSheetId(id);
                v.setFieldKey(key);
            }
            v.setValue(value);
            v.setUpdatedBy(p.id());
            v.setUpdatedAt(now);
            values.save(v);
        });

        sheet.setUpdatedAt(now);
        return new Saved(now);
    }

    // ---------- внутреннее ----------

    private Sheet dto(ShiftSheet sheet, UserPrincipal p) {
        // кто был инсайдом утром и вечером в этот день на этой точке
        List<ShiftSession> day = shifts.findByShiftDateAndOutletIdInOrderByStartedAtAsc(
                sheet.getSheetDate(), List.of(sheet.getOutletId()));
        String morning = scheduledInside(sheet, DayPart.MORNING).orElseGet(() -> insideName(day, DayPart.MORNING));
        String evening = scheduledInside(sheet, DayPart.EVENING).orElseGet(() -> insideName(day, DayPart.EVENING));

        Map<String, String> map = new HashMap<>();
        values.findBySheetId(sheet.getId()).forEach(v -> map.put(v.getFieldKey(), v.getValue()));

        String outletName = outlets.findById(sheet.getOutletId()).map(Outlet::getName).orElse("?");
        return new Sheet(sheet.getId(), sheet.getOutletId(), outletName, sheet.getSheetDate(),
                morning, evening, canEdit(p, sheet), map);
    }

    /** Инсайд из опубликованного графика, если есть. */
    private Optional<String> scheduledInside(ShiftSheet sheet, DayPart part) {
        return scheduleSlots.findByOutletIdAndSlotDateAndDayPartAndRole(
                        sheet.getOutletId(), sheet.getSheetDate(), part, ShiftRole.INSIDE)
                .filter(s -> s.isPublished() && s.getUserId() != null)
                .flatMap(s -> users.findById(s.getUserId()))
                .map(AppUser::getFullName);
    }

    private static String insideName(List<ShiftSession> day, DayPart part) {
        return day.stream()
                .filter(s -> s.getShiftRole() == ShiftRole.INSIDE && s.getDayPart() == part)
                .map(s -> s.getUser().getFullName())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    /** Править: суперадмин или тот, у кого открыта смена на этой точке. */
    private boolean canEdit(UserPrincipal p, ShiftSheet sheet) {
        if (p.role() == AccountRole.SUPER_ADMIN) return true;
        return shifts.findFirstByUserIdAndFinishedAtIsNull(p.id())
                .map(s -> s.getOutlet().getId().equals(sheet.getOutletId())
                        && s.getShiftDate().equals(sheet.getSheetDate()))
                .orElse(false);
    }

    private boolean canView(UserPrincipal p, ShiftSheet sheet) {
        if (p.role() == AccountRole.SUPER_ADMIN || canEdit(p, sheet)) return true;
        return users.findById(p.id())
                .map(u -> u.getOutlets().stream().anyMatch(o -> o.getId().equals(sheet.getOutletId())))
                .orElse(false);
    }

    private ShiftSheet sheet(Long id) {
        return sheets.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Чек-лист не найден"));
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этому чек-листу");
    }
}