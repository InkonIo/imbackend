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
    private final jakarta.persistence.EntityManager em;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final com.imdemo.im.ext.SchedAccess sched;
    private final com.imdemo.im.ext.SalesImportService salesImport;

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
    public Saved save(UserPrincipal p, Long id, Map<String, String> incoming) {
        ShiftSheet sheet = sheet(id);
        if (!canEdit(p, sheet)) throw forbidden();
        // утренние галочки и почасовка — только утреннему менеджеру, вечерние — вечернему (чужие поля пропускаем)
        String parts = partsFor(p, sheet);
        Map<String, String> changes = new java.util.LinkedHashMap<>();
        incoming.forEach((k, val) -> {
            if (allowedKey(parts, k)) changes.put(k, val);
        });

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

    // ---------- автозаполнение целей из отчёта iiko (sales_hour) ----------

    public static final String GROWTH_KEY = "sheet_goal_growth_pct";

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0d;
    }

    private static Map<String, Object> triple(double sales, double gc, double avg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sales", Math.round(sales));
        m.put("gc", Math.round(gc));
        m.put("avgCheck", Math.round(avg));
        return m;
    }

    /**
     * Для бланка: результат за вчера (SALES, GC, Av.check) и цели на сегодня =
     * среднее за 3 предыдущих такого же дня недели + процент роста (по умолчанию 1%).
     * Данные берутся из sales_hour (загружаются Excel-ом на вкладке «Аналитика» расписания).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> auto(UserPrincipal p, Long id) {
        ShiftSheet sheet = sheet(id);
        if (!canView(p, sheet)) throw forbidden();
        Map<String, Object> out = new LinkedHashMap<>();
        java.time.LocalDate day = sheet.getSheetDate();
        java.time.LocalDate prevDay = day.minusDays(1);
        int pct = sched.intSetting(GROWTH_KEY, 1);
        out.put("pct", pct);
        out.put("prevDate", prevDay.toString());

        List<Long> br = jdbc.queryForList("SELECT branch_id FROM outlet_ext_branch WHERE outlet_id = :o",
                Map.of("o", sheet.getOutletId()), Long.class);
        if (br.isEmpty()) {
            out.put("mapped", false);
            return out;
        }
        out.put("mapped", true);

        List<java.time.LocalDate> basisDays = List.of(day.minusDays(7), day.minusDays(14), day.minusDays(21));
        List<java.time.LocalDate> wanted = new ArrayList<>(basisDays);
        wanted.add(prevDay);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT day, sum(guests) AS g, sum(sales) AS s FROM sales_hour
            WHERE branch_id = :b AND day IN (:days) GROUP BY day HAVING sum(guests) > 0
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("b", br.get(0)).addValue("days", wanted));
        Map<java.time.LocalDate, double[]> byDay = new HashMap<>();
        for (Map<String, Object> r : rows) {
            java.sql.Date d = (java.sql.Date) r.get("day");
            byDay.put(d.toLocalDate(), new double[]{dbl(r.get("s")), dbl(r.get("g"))});
        }

        // почасовые цифры сегодняшнего дня (для таблиц «по часам»)
        List<Map<String, Object>> hrs = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT hour, guests, sales FROM sales_hour WHERE branch_id = :b AND day = :d ORDER BY hour",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("b", br.get(0)).addValue("d", day))) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("hour", ((Number) r.get("hour")).intValue());
            h.put("guests", ((Number) r.get("guests")).longValue());
            h.put("sales", Math.round(dbl(r.get("sales"))));
            hrs.add(h);
        }
        out.put("hours", hrs);

        double[] pv = byDay.get(prevDay);
        out.put("prev", pv == null ? null : triple(pv[0], pv[1], pv[0] / pv[1]));

        List<String> basis = new ArrayList<>();
        double sumS = 0, sumG = 0, sumA = 0;
        for (java.time.LocalDate d : basisDays) {
            double[] v = byDay.get(d);
            if (v == null) continue;
            basis.add(d.toString());
            sumS += v[0];
            sumG += v[1];
            sumA += v[0] / v[1];
        }
        out.put("basis", basis);

        // план по часам: среднее по этим же дням недели + процент роста
        List<Map<String, Object>> plan = new ArrayList<>();
        if (!basis.isEmpty()) {
            List<java.time.LocalDate> present = basis.stream().map(java.time.LocalDate::parse).toList();
            double k = 1 + pct / 100.0, n = present.size();
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT hour, sum(guests) AS g, sum(sales) AS s FROM sales_hour WHERE branch_id = :b AND day IN (:days) GROUP BY hour ORDER BY hour",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("b", br.get(0)).addValue("days", present))) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("hour", ((Number) r.get("hour")).intValue());
                h.put("gc", Math.round(dbl(r.get("g")) / n * k));
                h.put("sales", Math.round(dbl(r.get("s")) / n * k));
                plan.add(h);
            }
        }
        out.put("planHours", plan);

        // доли каналов: как в прошлом бланке этой точки, иначе стандартные
        Map<String, Object> pcts = new LinkedHashMap<>();
        for (Object[] r : em.createQuery(
                "select v.fieldKey, v.value from ShiftSheetValue v, ShiftSheet s where s.id = v.sheetId and s.outletId = :o "
                        + "and s.sheetDate < :d and v.fieldKey like 'pct.%' order by s.sheetDate desc", Object[].class)
                .setParameter("o", sheet.getOutletId()).setParameter("d", day).setMaxResults(50).getResultList()) {
            pcts.putIfAbsent((String) r[0], r[1]);
        }
        pcts.putIfAbsent("pct.counter", "23");
        pcts.putIfAbsent("pct.kiosk", "51");
        pcts.putIfAbsent("pct.delivery", "20");
        pcts.putIfAbsent("pct.mobile", "5");
        pcts.putIfAbsent("pct.postamat", "1");
        out.put("pcts", pcts);
        if (basis.isEmpty()) {
            out.put("goal", null);
        } else {
            double k = 1 + pct / 100.0, n = basis.size();
            out.put("goal", triple(sumS / n * k, sumG / n * k, sumA / n * k));
        }
        return out;
    }

    /** Загрузка Excel «Почасовая выручка» прямо из бланка (кто может править бланк). */
    @Transactional
    public Map<String, Object> importSales(UserPrincipal p, Long id, String fileName, String data) {
        ShiftSheet sheet = sheet(id);
        if (!canEdit(p, sheet)) throw forbidden();
        List<Long> br = jdbc.queryForList("SELECT branch_id FROM outlet_ext_branch WHERE outlet_id = :o",
                Map.of("o", sheet.getOutletId()), Long.class);
        if (br.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Точка не привязана к ресторану из тайм-трекера");
        }
        return salesImport.importXlsx(br.get(0), p.id(), fileName, data);
    }

    // ---------- архив ----------

    public record ListRow(Long id, Long outletId, String outletName, java.time.LocalDate date,
                          String morningName, String eveningName, long ticks, OffsetDateTime updatedAt) {}

    /** Архив бланков: супер-админ видит все точки, директор только свои. Остальным нельзя. */
    @Transactional(readOnly = true)
    public List<ListRow> list(UserPrincipal p, java.time.LocalDate from, java.time.LocalDate to, Long outletId) {
        if (p.role() != AccountRole.SUPER_ADMIN && p.role() != AccountRole.DIRECTOR) throw forbidden();
        if (from == null || to == null || to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Укажи период");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 93) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Период не больше 3 месяцев");
        }
        Set<Long> allowed = null; // null = все
        if (p.role() == AccountRole.DIRECTOR) {
            allowed = users.findById(p.id())
                    .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                    .orElse(Set.of());
            if (allowed.isEmpty()) return List.of();
            if (outletId != null && !allowed.contains(outletId)) throw forbidden();
        }
        StringBuilder q = new StringBuilder("select s from ShiftSheet s where s.sheetDate between :f and :t");
        if (outletId != null) q.append(" and s.outletId = :o");
        else if (allowed != null) q.append(" and s.outletId in :os");
        q.append(" order by s.sheetDate desc, s.outletId");
        var query = em.createQuery(q.toString(), ShiftSheet.class).setParameter("f", from).setParameter("t", to);
        if (outletId != null) query.setParameter("o", outletId);
        else if (allowed != null) query.setParameter("os", allowed);
        List<ShiftSheet> rows = query.setMaxResults(300).getResultList();
        if (rows.isEmpty()) return List.of();

        // сколько галочек поставлено в каждом бланке (одним запросом)
        Map<Long, Long> ticks = new HashMap<>();
        List<Long> ids = rows.stream().map(ShiftSheet::getId).toList();
        for (Object[] r : em.createQuery(
                "select v.sheetId, count(v) from ShiftSheetValue v where v.sheetId in :ids and v.fieldKey like 'chk.%' and v.value = '1' group by v.sheetId",
                Object[].class).setParameter("ids", ids).getResultList()) {
            ticks.put((Long) r[0], (Long) r[1]);
        }
        Map<Long, String> outletNames = new HashMap<>();
        return rows.stream().map(sh -> {
            List<ShiftSession> day = shifts.findByShiftDateAndOutletIdInOrderByStartedAtAsc(
                    sh.getSheetDate(), List.of(sh.getOutletId()));
            String morning = scheduledInside(sh, DayPart.MORNING).orElseGet(() -> insideName(day, DayPart.MORNING));
            String evening = scheduledInside(sh, DayPart.EVENING).orElseGet(() -> insideName(day, DayPart.EVENING));
            String name = outletNames.computeIfAbsent(sh.getOutletId(),
                    id -> outlets.findById(id).map(Outlet::getName).orElse("?"));
            return new ListRow(sh.getId(), sh.getOutletId(), name, sh.getSheetDate(), morning, evening,
                    ticks.getOrDefault(sh.getId(), 0L), sh.getUpdatedAt());
        }).toList();
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
        map.put("_parts", partsFor(p, sheet)); // служебное поле: какие части бланка может править пользователь ('m', 'e', 'me' или '')

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

    private static final Pattern PART_KEY =
            Pattern.compile("^(?:(?:chk|exp)\\.[^.]+\\.(m|e)|(?:hr|status)\\.(m|e)\\..+)$");

    /** Какие части бланка (m — утро, e — вечер) может править пользователь. */
    private String partsFor(UserPrincipal p, ShiftSheet sheet) {
        // супер-админ с открытой сменой на этой точке работает как обычный менеджер своей части дня;
        // без смены — видит и правит обе
        boolean admin = p.role() == AccountRole.SUPER_ADMIN;
        return shifts.findFirstByUserIdAndFinishedAtIsNull(p.id())
                .filter(s -> s.getOutlet().getId().equals(sheet.getOutletId())
                        && s.getShiftDate().equals(sheet.getSheetDate()))
                .map(s -> s.getDayPart() == DayPart.MORNING ? "m" : s.getDayPart() == DayPart.EVENING ? "e" : "me")
                .orElse(admin ? "me" : "");
    }

    /** Поле общее (цели, приоритеты, доли…) или относится к части, которую можно править. */
    private static boolean allowedKey(String parts, String key) {
        java.util.regex.Matcher m = PART_KEY.matcher(key);
        if (!m.matches()) return true;
        String part = m.group(1) != null ? m.group(1) : m.group(2);
        return parts.contains(part);
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