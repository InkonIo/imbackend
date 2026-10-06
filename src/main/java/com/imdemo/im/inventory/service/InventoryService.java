package com.imdemo.im.inventory.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.inventory.domain.*;
import com.imdemo.im.inventory.dto.InventoryDto.*;
import com.imdemo.im.inventory.repository.InventoryCountRepository;
import com.imdemo.im.inventory.repository.InventoryLineRepository;
import com.imdemo.im.inventory.repository.InventoryListRepository;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.service.AuditService;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

    private final InventoryListRepository lists;
    private final InventoryCountRepository counts;
    private final InventoryLineRepository lines;
    private final ShiftSessionRepository shifts;
    private final OutletRepository outlets;
    private final UserRepository users;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    private record Prev(Double cs, Double slv, Double ea, Double total, LocalDate date) {}

    // ================= начать / текущая =================

    /** Инвентаризация моей открытой смены, если уже начата. */
    @Transactional(readOnly = true)
    public Optional<Count> current(UserPrincipal p) {
        return shifts.findFirstByUserIdAndFinishedAtIsNull(p.id())
                .flatMap(s -> {
                    InventoryList list = listForOrNull(s.getShiftDate());
                    return list == null ? Optional.empty()
                            : counts.findByOutletIdAndListIdAndCountDate(s.getOutlet().getId(), list.getId(), s.getShiftDate());
                })
                .map(c -> toDto(c, p));
    }

    @Transactional
    public Count start(UserPrincipal p, StartRequest r) {
        ShiftSession shift = shifts.findFirstByUserIdAndFinishedAtIsNull(p.id()).orElse(null);
        Long outletId;
        LocalDate date;
        if (shift != null) {
            outletId = shift.getOutlet().getId();
            date = shift.getShiftDate(); // после полуночи остаётся дата смены
        } else if (p.role() == AccountRole.MANAGER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Сначала начни смену");
        } else {
            if (r == null || r.outletId() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Выбери точку");
            outletId = r.outletId();
            date = r.date() != null ? r.date() : LocalDate.now(ZONE);
        }
        if (!canAccessOutlet(p, outletId)) throw forbidden();

        InventoryList list = listForOrNull(date);
        if (list == null) throw new ApiException(HttpStatus.BAD_REQUEST, "На этот день лист инвентаризации не настроен");

        Optional<InventoryCount> existing = counts.findByOutletIdAndListIdAndCountDate(outletId, list.getId(), date);
        if (existing.isPresent()) return toDto(existing.get(), p);

        InventoryCount c = new InventoryCount();
        c.setList(list);
        c.setOutletId(outletId);
        c.setCountDate(date);
        c.setUserId(p.id());
        c.setShiftId(shift == null ? null : shift.getId());

        Map<Long, Prev> prev = loadPrev(outletId, date);
        for (InventoryListItem li : list.getItems()) {
            if (!li.getProduct().isActive()) continue;
            InventoryLine l = new InventoryLine();
            l.setInventory(c);
            l.setProduct(li.getProduct());
            l.setZone(li.getZone());
            l.setSortOrder(li.getSortOrder());
            Prev pv = prev.get(li.getProduct().getId());
            if (pv != null) {
                l.setPrevCs(pv.cs());
                l.setPrevSlv(pv.slv());
                l.setPrevEa(pv.ea());
                l.setPrevTotal(pv.total());
                l.setPrevDate(pv.date());
            }
            c.getLines().add(l);
        }
        counts.saveAndFlush(c);
        audit.log(AuditEventType.INVENTORY_STARTED, shift, "inventory", c.getId(),
                list.getTitle() + " лист · позиций " + c.getLines().size());
        return toDto(c, p);
    }

    // ================= ввод =================

    @Transactional
    public Line updateLine(UserPrincipal p, Long countId, Long lineId, LineRequest r) {
        InventoryCount c = count(countId);
        ensureEditable(p, c);
        InventoryLine l = lines.findById(lineId)
                .filter(x -> x.getInventory().getId().equals(countId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Позиция не найдена"));
        InventoryProduct pr = l.getProduct();

        Double cs = whole(r.cs(), "Кейсы");
        Double slv = whole(r.slv(), "Сливы");
        Double ea = r.ea();
        if (ea != null && !pr.allowsDecimal() && ea % 1 != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "«" + pr.getName() + "»: только целые " + pr.getUnit());
        }
        boolean none = r.none();
        if (none) {
            cs = null;
            slv = null;
            ea = null;
        }

        boolean changed = !Objects.equals(cs, l.getCs()) || !Objects.equals(slv, l.getSlv())
                || !Objects.equals(ea, l.getEa()) || none != l.isNoneFlag();
        l.setCs(cs);
        l.setSlv(slv);
        l.setEa(ea);
        l.setNoneFlag(none);
        l.setTotal(total(pr, l));
        // подтверждение «пересчитал, верно» сбрасывается, если цифры поменялись
        l.setConfirmed(!changed && r.confirmed());
        l.setUpdatedAt(OffsetDateTime.now());
        return line(l);
    }

    @Transactional
    public Count submit(UserPrincipal p, Long countId) {
        InventoryCount c = count(countId);
        ensureEditable(p, c);

        long empty = c.getLines().stream().filter(l -> !l.isFilled()).count();
        if (empty > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Не заполнено позиций: " + empty + ". Если товара нет, нажми «Нет на складе»");
        }
        long unchecked = c.getLines().stream().filter(l -> warning(l) != null && !l.isConfirmed()).count();
        if (unchecked > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Есть позиции с большим расхождением (" + unchecked + "). Пересчитай и подтверди");
        }

        c.setStatus(CountStatus.SUBMITTED);
        c.setSubmittedAt(OffsetDateTime.now());
        long recounted = c.getLines().stream().filter(l -> warning(l) != null).count();
        ShiftSession shift = c.getShiftId() == null ? null : shifts.findById(c.getShiftId()).orElse(null);
        audit.log(AuditEventType.INVENTORY_SUBMITTED, shift, "inventory", c.getId(),
                c.getList().getTitle() + " лист · позиций " + c.getLines().size()
                        + (recounted > 0 ? " · пересчитано с расхождением: " + recounted : ""));
        return toDto(c, p);
    }

    // ================= просмотр =================

    @Transactional(readOnly = true)
    public Count get(UserPrincipal p, Long countId) {
        InventoryCount c = count(countId);
        if (!canView(p, c)) throw forbidden();
        return toDto(c, p);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UserPrincipal p, LocalDate from, LocalDate to) {
        List<InventoryCount> found;
        if (p.role() == AccountRole.SUPER_ADMIN) {
            found = counts.findByCountDateBetweenOrderByCountDateDescIdDesc(from, to);
        } else if (p.role() == AccountRole.DIRECTOR) {
            Set<Long> mine = myOutlets(p);
            found = mine.isEmpty() ? List.of()
                    : counts.findByCountDateBetweenAndOutletIdInOrderByCountDateDescIdDesc(from, to, mine);
        } else {
            found = counts.findByCountDateBetweenOrderByCountDateDescIdDesc(from, to).stream()
                    .filter(c -> c.getUserId().equals(p.id())).toList();
        }
        Map<Long, String> outletNames = outlets.findAll().stream()
                .collect(Collectors.toMap(Outlet::getId, Outlet::getName));
        return found.stream().map(c -> new Summary(c.getId(), c.getList().getTitle(),
                outletNames.getOrDefault(c.getOutletId(), "?"), c.getCountDate(), userName(c.getUserId()),
                c.getStatus(), c.getSubmittedAt(), c.getLines().size(),
                (int) c.getLines().stream().filter(InventoryLine::isFilled).count(),
                (int) c.getLines().stream().filter(l -> warning(l) != null && l.isConfirmed()).count()))
                .toList();
    }

    // ================= правила =================

    /** Итог в шт/кг/л по фасовке. null, если фасовка неизвестна для введённых кейсов или сливов. */
    static Double total(InventoryProduct pr, InventoryLine l) {
        if (l.isNoneFlag()) return 0.0;
        if (!l.isFilled()) return null;
        double cs = nz(l.getCs()), slv = nz(l.getSlv()), ea = nz(l.getEa());
        if (cs > 0 && pr.getCaseQty() == null) return null;
        if (slv > 0 && pr.getSleeveQty() == null) return null;
        double t = cs * nz(pr.getCaseQty()) + slv * nz(pr.getSleeveQty()) + ea;
        return Math.round(t * 100) / 100.0;
    }

    /** Похоже на опечатку? Текст предупреждения или null. */
    static String warning(InventoryLine l) {
        if (!l.isFilled() || l.isNoneFlag()) return null;
        double cs = nz(l.getCs()), slv = nz(l.getSlv()), ea = nz(l.getEa());
        if (cs > 60 || slv > 400 || ea > 5000) return "Очень большое число. Проверь, нет ли лишней цифры";
        if (l.getPrevDate() == null) return null;
        String unit = l.getProduct().getUnit();
        if (l.getTotal() != null && l.getPrevTotal() != null) {
            return jump(l.getTotal(), l.getPrevTotal())
                    ? "В прошлый раз было " + fmt(l.getPrevTotal()) + " " + unit + ", сейчас " + fmt(l.getTotal()) + ". Пересчитай"
                    : null;
        }
        if (jump(cs, nz(l.getPrevCs()))) return "Кейсы: в прошлый раз " + fmt(nz(l.getPrevCs())) + ", сейчас " + fmt(cs) + ". Пересчитай";
        if (jump(slv, nz(l.getPrevSlv()))) return "Сливы: в прошлый раз " + fmt(nz(l.getPrevSlv())) + ", сейчас " + fmt(slv) + ". Пересчитай";
        if (jump(ea, nz(l.getPrevEa()))) return "Россыпью: в прошлый раз " + fmt(nz(l.getPrevEa())) + ", сейчас " + fmt(ea) + ". Пересчитай";
        return null;
    }

    private static boolean jump(double now, double before) {
        if (before == 0) return now >= 30;
        double hi = Math.max(now, before), lo = Math.min(now, before);
        return hi >= lo * 3 && hi - lo >= 5;
    }

    // ================= внутреннее =================

    private InventoryList listForOrNull(LocalDate date) {
        int dow = date.getDayOfWeek().getValue();
        return lists.findByActiveTrueOrderByIdAsc().stream().filter(l -> l.runsOn(dow)).findFirst().orElse(null);
    }

    /** Последний сданный подсчёт каждого товара на этой точке до указанной даты. */
    private Map<Long, Prev> loadPrev(Long outletId, LocalDate date) {
        Map<Long, Prev> map = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (l.product_id) l.product_id, l.cs, l.slv, l.ea, l.total, l.none_flag, c.count_date
                FROM inventory_line l
                JOIN inventory_count c ON c.id = l.count_id
                WHERE c.outlet_id = ? AND c.status = 'SUBMITTED' AND c.count_date < ?
                  AND (l.none_flag OR l.cs IS NOT NULL OR l.slv IS NOT NULL OR l.ea IS NOT NULL)
                ORDER BY l.product_id, c.count_date DESC, c.id DESC
                """, rs -> {
                    boolean none = rs.getBoolean("none_flag");
                    map.put(rs.getLong("product_id"), new Prev(
                            none ? 0.0 : dbl(rs, "cs"), none ? 0.0 : dbl(rs, "slv"), none ? 0.0 : dbl(rs, "ea"),
                            none ? 0.0 : dbl(rs, "total"), rs.getDate("count_date").toLocalDate()));
                }, outletId, java.sql.Date.valueOf(date));
        return map;
    }

    private Count toDto(InventoryCount c, UserPrincipal p) {
        List<Line> ls = c.getLines().stream().map(InventoryService::line).toList();
        int filled = (int) ls.stream().filter(Line::filled).count();
        int warnings = (int) ls.stream().filter(l -> l.warning() != null).count();
        String outletName = outlets.findById(c.getOutletId()).map(Outlet::getName).orElse("?");
        return new Count(c.getId(), c.getList().getCode(), c.getList().getTitle(), c.getOutletId(), outletName,
                c.getCountDate(), c.getUserId(), userName(c.getUserId()), c.getStatus(),
                c.getStartedAt(), c.getSubmittedAt(), ls.size(), filled, warnings, isEditable(p, c), ls);
    }

    private static Line line(InventoryLine l) {
        InventoryProduct pr = l.getProduct();
        return new Line(l.getId(), l.getZone(), l.getSortOrder(), pr.getName(), pr.getPackText(),
                pr.getCaseQty(), pr.getSleeveQty(), pr.getUnit(), pr.allowsDecimal(),
                l.getCs(), l.getSlv(), l.getEa(), l.isNoneFlag(), l.getTotal(), l.isFilled(),
                l.getPrevCs(), l.getPrevSlv(), l.getPrevEa(), l.getPrevTotal(), l.getPrevDate(),
                warning(l), l.isConfirmed());
    }

    /** Править может тот, кто начал, любой с открытой сменой на этой точке, и суперадмин. Только черновик. */
    private boolean isEditable(UserPrincipal p, InventoryCount c) {
        if (c.getStatus() != CountStatus.DRAFT) return false;
        if (p.role() == AccountRole.SUPER_ADMIN || c.getUserId().equals(p.id())) return true;
        return shifts.findFirstByUserIdAndFinishedAtIsNull(p.id())
                .map(s -> s.getOutlet().getId().equals(c.getOutletId())).orElse(false);
    }

    private void ensureEditable(UserPrincipal p, InventoryCount c) {
        if (c.getStatus() != CountStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "Инвентаризация уже сдана, изменить нельзя");
        }
        if (!isEditable(p, c)) throw forbidden();
    }

    private boolean canView(UserPrincipal p, InventoryCount c) {
        if (p.role() == AccountRole.SUPER_ADMIN || c.getUserId().equals(p.id())) return true;
        if (p.role() == AccountRole.DIRECTOR) return myOutlets(p).contains(c.getOutletId());
        return isEditable(p, c);
    }

    private boolean canAccessOutlet(UserPrincipal p, Long outletId) {
        return p.role() == AccountRole.SUPER_ADMIN || myOutlets(p).contains(outletId);
    }

    private Set<Long> myOutlets(UserPrincipal p) {
        return users.findById(p.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private String userName(Long id) {
        return users.findById(id).map(AppUser::getFullName).orElse("?");
    }

    private InventoryCount count(Long id) {
        return counts.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Инвентаризация не найдена"));
    }

    private static Double whole(Double v, String field) {
        if (v != null && v % 1 != 0) throw new ApiException(HttpStatus.BAD_REQUEST, field + ": только целое число");
        return v;
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static String fmt(double v) {
        return v % 1 == 0 ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0).replace('.', ',');
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой инвентаризации");
    }
}