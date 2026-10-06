package com.imdemo.im.schedule.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.notify.domain.NotificationType;
import com.imdemo.im.notify.service.NotificationService;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.schedule.domain.*;
import com.imdemo.im.schedule.dto.ScheduleDto.*;
import com.imdemo.im.schedule.repository.*;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final List<ShiftRole> ORDER =
            List.of(ShiftRole.INSIDE, ShiftRole.PRODUCTION_MANAGER, ShiftRole.SERVICE_MANAGER);
    private static final int MAX_IN_ROW = 5;
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] DOW = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final String[] DOW_SHORT = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    private static final Map<ShiftRole, String> ROLE = Map.of(
            ShiftRole.INSIDE, "Инсайд", ShiftRole.PRODUCTION_MANAGER, "Кухня", ShiftRole.SERVICE_MANAGER, "Прилавок");
    private static final Map<AbsenceKind, String> KIND = Map.of(
            AbsenceKind.VACATION, "отпуск", AbsenceKind.SICK, "больничный", AbsenceKind.OTHER, "отсутствует");

    /** Промеж: не раньше 10:00 (иначе утро) и не позже 23:00 (иначе вечер). */
    private static final LocalTime MIDDLE_FROM = LocalTime.of(10, 0);
    private static final LocalTime MIDDLE_TO = LocalTime.of(23, 0);
    /** «Поздно закончил»: вечер или промеж после 21:00. */
    private static final LocalTime LATE_END = LocalTime.of(21, 0);

    private final ScheduleSlotRepository slots;
    private final StaffProfileRepository profiles;
    private final StaffAbsenceRepository absences;
    private final StaffLimitRepository limits;
    private final OutletStaffingRepository staffing;
    private final UserRepository users;
    private final OutletRepository outlets;
    private final NotificationService notifications;

    private record Person(Long id, String name, String login, JobTitle job, boolean canInside,
                          boolean schedulable, int maxWeek) {}

    /** Клетка штатки на день: утро/вечер (роль) или промеж (роль + время). */
    private record Cell(DayPart part, ShiftRole role, LocalTime start, LocalTime end) {}

    /** Что человек делает в этот день (для правил отдыха). */
    private record Work(DayPart part, LocalTime start, LocalTime end) {}

    // ================= просмотр =================

    @Transactional(readOnly = true)
    public Board board(UserPrincipal p, Long outletId, LocalDate from, LocalDate to) {
        checkOutlet(p, outletId);
        checkRange(from, to);
        return buildBoard(outletId, from, to);
    }

    // ================= генерация =================

    @Transactional
    public Board generate(UserPrincipal p, GenerateRequest r) {
        checkOutlet(p, r.outletId());
        Long outletId = r.outletId();
        LocalDate from = r.from();
        LocalDate to = from.plusDays(r.days() - 1);
        List<ShiftRole> morning = ordered(r.morning());
        List<ShiftRole> evening = ordered(r.evening());
        List<MiddleShift> middle = checkMiddle(r.middle());
        if (morning.isEmpty() && evening.isEmpty() && middle.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Выбери хотя бы одну роль");
        }

        OutletStaffing st = staffing.findById(outletId).orElseGet(() -> {
            OutletStaffing n = new OutletStaffing();
            n.setOutletId(outletId);
            return n;
        });
        st.setMorning(join(morning));
        st.setEvening(join(evening));
        st.setMiddle(formatMiddle(middle));
        staffing.save(st);

        List<Cell> plan = plan(morning, evening, middle);
        Set<String> planned = plan.stream().map(c -> partKey(c.part(), c.role(), c.start())).collect(Collectors.toSet());

        List<Person> staff = staffOf(outletId);
        Map<Long, Person> byId = staff.stream().collect(Collectors.toMap(Person::id, Function.identity()));
        List<Person> pool = staff.stream().filter(Person::schedulable).toList();
        List<Long> ids = staff.stream().map(Person::id).toList();
        List<StaffAbsence> abs = ids.isEmpty() ? List.of() : absences.overlapping(ids, from, to);
        Map<Long, List<StaffLimit>> lim = limitsOf(ids);
        OffsetDateTime now = OffsetDateTime.now();

        // 1. существующие клетки окна
        Map<String, ScheduleSlot> grid = new HashMap<>();
        List<ScheduleSlot> removed = new ArrayList<>();
        for (ScheduleSlot s : slots.findByOutletIdAndSlotDateBetweenOrderBySlotDateAsc(outletId, from, to)) {
            if (!planned.contains(partKey(s.getDayPart(), s.getRole(), s.getStartTime()))) {
                // роль или промеж убрали из штатки → клетку удаляем, даже опубликованную
                if (s.isPublished() && s.getUserId() != null) removed.add(s);
                slots.delete(s);
                continue;
            }
            if (s.getUserId() != null && conflictOf(s, byId, abs) != null) {
                // отпуск / больничный / нельзя инсайдом → снимаем всегда
                s.setUserId(null);
                s.setPublished(false);
                s.setUpdatedAt(now);
            } else if (s.getUserId() != null && !s.isPublished() && limitOf(s, lim) != null) {
                // черновик против пожелания менеджера → освобождаем
                s.setUserId(null);
            }
            if (!s.isPublished() && !r.keepFilled()) s.setUserId(null);
            grid.put(key(s), s);
        }
        slots.flush();
        notifyRemoved(removed, outletId, p.id());

        // 2. занятость людей: клетки этой точки в окне + неделя до окна и другие точки
        Busy busy = new Busy();
        for (ScheduleSlot s : grid.values()) {
            if (s.getUserId() != null) busy.add(s, true);
        }
        if (!ids.isEmpty()) {
            for (ScheduleSlot s : slots.findByUserIdInAndSlotDateBetween(ids, from.minusDays(7), to.plusDays(1))) {
                boolean thisWindow = s.getOutletId().equals(outletId)
                        && !s.getSlotDate().isBefore(from) && !s.getSlotDate().isAfter(to);
                if (s.getUserId() != null && !thisWindow) {
                    boolean inWindow = !s.getSlotDate().isBefore(from) && !s.getSlotDate().isAfter(to);
                    busy.add(s, inWindow);
                }
            }
        }

        // 3. день за днём: утро → вечер → промежи; внутри части дня сначала инсайд
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate day = d;
            for (Cell c : plan) {
                ScheduleSlot slot = grid.computeIfAbsent(key(day, c.part(), c.role(), c.start()), k -> newSlot(outletId, day, c));
                if (slot.getUserId() != null) continue;
                Person pick = pick(pool, busy, abs, lim, day, c, grid, byId);
                if (pick == null) continue; // останется «⚠ пусто»
                slot.setUserId(pick.id());
                slot.setPublished(false);
                slot.setUpdatedAt(now);
                busy.add(slot, true);
            }
        }
        slots.saveAll(grid.values());
        return buildBoard(outletId, from, to);
    }

    private Person pick(List<Person> pool, Busy busy, List<StaffAbsence> abs, Map<Long, List<StaffLimit>> lim,
                        LocalDate d, Cell c, Map<String, ScheduleSlot> grid, Map<Long, Person> byId) {
        boolean lead = hasLead(d, c.part(), grid, byId);
        Work today = new Work(c.part(), c.start(), c.end());
        return pool.stream()
                .filter(x -> !absentOn(abs, x.id(), d))
                .filter(x -> !limited(lim, x.id(), d, c.part()))                       // пожелания менеджера
                .filter(x -> !busy.worksOn(x.id(), d))                                  // одна смена в день
                .filter(x -> c.role() != ShiftRole.INSIDE || x.canInside())
                .filter(x -> x.job() != JobTitle.TRAINEE || lead)                       // стажёр не один
                .filter(x -> !(lateFinish(busy.on(x.id(), d.minusDays(1))) && earlyStart(today)))
                .filter(x -> !(lateFinish(today) && earlyStart(busy.on(x.id(), d.plusDays(1)))))
                .filter(x -> busy.week(x.id(), d) < x.maxWeek())                       // 5/2
                .filter(x -> busy.streak(x.id(), d) < MAX_IN_ROW)
                .min(Comparator.comparingDouble(x -> cost(x, busy, d, c)))
                .orElse(null);
    }

    /** Чем меньше, тем лучше кандидат. */
    private static double cost(Person x, Busy b, LocalDate d, Cell c) {
        int[] w = b.window(x.id()); // [смен, утро, вечер, промеж, инсайд]
        double cost = 10.0 * b.week(x.id(), d) + 3.0 * w[0];
        if (c.part() == DayPart.MORNING) cost += 2.0 * Math.max(0, w[1] - w[2]); // утро и вечер поровну
        if (c.part() == DayPart.EVENING) cost += 2.0 * Math.max(0, w[2] - w[1]);
        if (c.part() == DayPart.MIDDLE) cost += 1.5 * w[3];                      // промежи по очереди
        if (c.role() == ShiftRole.INSIDE) cost += 2.0 * w[4];                    // инсайды по очереди
        else if (x.canInside()) cost += 4.0;                                     // беречь тех, кто может инсайдом
        cost += b.streak(x.id(), d);
        if (x.job() == JobTitle.DIRECTOR) cost += 100;
        cost += Math.floorMod(x.id() * 31 + d.toEpochDay(), 7) * 0.1;
        return cost;
    }

    /** Есть ли в эту часть дня уже не стажёр (для промежа: утром или вечером). */
    private static boolean hasLead(LocalDate d, DayPart part, Map<String, ScheduleSlot> grid, Map<Long, Person> byId) {
        return grid.values().stream()
                .filter(s -> s.getSlotDate().equals(d) && s.getUserId() != null)
                .filter(s -> part == DayPart.MIDDLE ? s.getDayPart() != DayPart.MIDDLE : s.getDayPart() == part)
                .map(s -> byId.get(s.getUserId()))
                .anyMatch(x -> x != null && x.job() != JobTitle.TRAINEE);
    }

    private static boolean lateFinish(Work w) {
        return w != null && (w.part() == DayPart.EVENING
                || (w.part() == DayPart.MIDDLE && w.end() != null && w.end().isAfter(LATE_END)));
    }

    private static boolean earlyStart(Work w) {
        return w != null && (w.part() == DayPart.MORNING
                || (w.part() == DayPart.MIDDLE && w.start() != null && w.start().isBefore(LocalTime.NOON)));
    }

    /** Кто когда работает: для правил и подсчёта. */
    private static final class Busy {
        private final Map<Long, Map<LocalDate, Work>> days = new HashMap<>();
        private final Map<Long, int[]> window = new HashMap<>();

        void add(ScheduleSlot s, boolean inWindow) {
            Long u = s.getUserId();
            days.computeIfAbsent(u, x -> new HashMap<>())
                    .put(s.getSlotDate(), new Work(s.getDayPart(), s.getStartTime(), s.getEndTime()));
            if (inWindow) {
                int[] w = window.computeIfAbsent(u, x -> new int[5]);
                w[0]++;
                switch (s.getDayPart()) {
                    case MORNING -> w[1]++;
                    case EVENING -> w[2]++;
                    case MIDDLE -> w[3]++;
                }
                if (s.getRole() == ShiftRole.INSIDE) w[4]++;
            }
        }

        boolean worksOn(Long u, LocalDate d) {
            return days.getOrDefault(u, Map.of()).containsKey(d);
        }

        Work on(Long u, LocalDate d) {
            return days.getOrDefault(u, Map.of()).get(d);
        }

        int week(Long u, LocalDate d) {
            LocalDate monday = d.with(DayOfWeek.MONDAY);
            int c = 0;
            for (int i = 0; i < 7; i++) if (worksOn(u, monday.plusDays(i))) c++;
            return c;
        }

        int streak(Long u, LocalDate d) {
            int c = 0;
            for (LocalDate x = d.minusDays(1); worksOn(u, x); x = x.minusDays(1)) c++;
            return c;
        }

        int[] window(Long u) {
            return window.getOrDefault(u, new int[5]);
        }
    }

    // ================= ручная правка =================

    @Transactional
    public Result setSlot(UserPrincipal p, SlotRequest r) {
        checkOutlet(p, r.outletId());
        Cell cell = cellOf(r.dayPart(), r.role(), r.startTime(), r.endTime());
        ScheduleSlot slot = findSlot(r.outletId(), r.date(), cell)
                .orElseGet(() -> slots.saveAndFlush(newSlot(r.outletId(), r.date(), cell)));
        List<String> warns = r.userId() == null ? List.of()
                : validate(r.outletId(), r.userId(), r.date(), cell, ignoring(slot));
        slot.setUserId(r.userId());
        slot.setPublished(false);
        slot.setUpdatedAt(OffsetDateTime.now());
        return new Result(warns);
    }

    @Transactional
    public Result swap(UserPrincipal p, SwapRequest r) {
        checkOutlet(p, r.outletId());
        Cell ca = cellOf(r.a().dayPart(), r.a().role(), r.a().startTime(), r.a().endTime());
        Cell cb = cellOf(r.b().dayPart(), r.b().role(), r.b().startTime(), r.b().endTime());
        ScheduleSlot a = findSlot(r.outletId(), r.a().date(), ca)
                .orElseGet(() -> slots.saveAndFlush(newSlot(r.outletId(), r.a().date(), ca)));
        ScheduleSlot b = findSlot(r.outletId(), r.b().date(), cb)
                .orElseGet(() -> slots.saveAndFlush(newSlot(r.outletId(), r.b().date(), cb)));
        if (a.getId().equals(b.getId())) return new Result(List.of());
        Long ua = a.getUserId();
        Long ub = b.getUserId();
        Set<Long> ignore = ignoring(a, b);
        List<String> warns = new ArrayList<>();
        if (ub != null) warns.addAll(validate(r.outletId(), ub, a.getSlotDate(), ca, ignore));
        if (ua != null) warns.addAll(validate(r.outletId(), ua, b.getSlotDate(), cb, ignore));
        OffsetDateTime now = OffsetDateTime.now();
        a.setUserId(ub);
        b.setUserId(ua);
        a.setPublished(false);
        b.setPublished(false);
        a.setUpdatedAt(now);
        b.setUpdatedAt(now);
        return new Result(warns);
    }

    /** Жёсткие правила → ошибка; рискованное и пожелания → предупреждение. */
    private List<String> validate(Long outletId, Long userId, LocalDate date, Cell cell, Set<Long> ignore) {
        Person x = staffOf(outletId).stream().filter(s -> s.id().equals(userId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Сотрудник не закреплён за этой точкой"));
        if (cell.role() == ShiftRole.INSIDE && !x.canInside()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    x.name() + " не ставится инсайдом. Это меняется во вкладке «Сотрудники»");
        }
        absences.overlapping(List.of(userId), date, date).stream().findFirst().ifPresent(a -> {
            throw new ApiException(HttpStatus.BAD_REQUEST, x.name() + ": " + KIND.get(a.getKind()) + " "
                    + a.getDateFrom().format(DM) + "–" + a.getDateTo().format(DM));
        });

        List<String> warns = new ArrayList<>();
        limits.findByUserIdOrderByIdAsc(userId).stream()
                .filter(l -> l.blocks(date, cell.part()))
                .findFirst()
                .ifPresent(l -> warns.add("🙅 " + x.name() + " просил не ставить: " + describe(l)));

        Work today = new Work(cell.part(), cell.start(), cell.end());
        int week = 0;
        LocalDate monday = date.with(DayOfWeek.MONDAY);
        for (ScheduleSlot s : slots.findByUserIdInAndSlotDateBetween(List.of(userId), monday.minusDays(1), monday.plusDays(7))) {
            if (ignore.contains(s.getId())) continue;
            LocalDate sd = s.getSlotDate();
            if (sd.equals(date)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, x.name() + " уже стоит в этот день: " + cellLabel(s));
            }
            Work w = new Work(s.getDayPart(), s.getStartTime(), s.getEndTime());
            if (sd.equals(date.minusDays(1)) && lateFinish(w) && earlyStart(today)) {
                warns.add(x.name() + ": накануне поздно закончил, а тут рано начинать");
            }
            if (sd.equals(date.plusDays(1)) && lateFinish(today) && earlyStart(w)) {
                warns.add(x.name() + ": поздно закончит, а на следующий день рано начинать");
            }
            if (!sd.isBefore(monday) && sd.isBefore(monday.plusDays(7))) week++;
        }
        if (week + 1 > x.maxWeek()) warns.add(x.name() + ": больше " + x.maxWeek() + " смен на этой неделе");
        return warns;
    }

    // ================= публикация =================

    @Transactional
    public Map<String, Integer> publish(UserPrincipal p, PublishRequest r) {
        checkOutlet(p, r.outletId());
        checkRange(r.from(), r.to());
        String outletName = outletName(r.outletId());
        List<ScheduleSlot> list = slots.findByOutletIdAndSlotDateBetweenOrderBySlotDateAsc(r.outletId(), r.from(), r.to())
                .stream().filter(s -> s.getUserId() != null).toList();

        int changed = 0;
        for (ScheduleSlot s : list) {
            if (!s.isPublished()) {
                s.setPublished(true);
                changed++;
            }
        }

        Map<Long, List<ScheduleSlot>> byUser = list.stream().collect(Collectors.groupingBy(ScheduleSlot::getUserId));
        byUser.forEach((userId, mine) -> {
            String body = mine.stream().sorted(SLOT_ORDER).map(ScheduleService::line).collect(Collectors.joining("\n"));
            notifications.send(userId, NotificationType.SCHEDULE,
                    "🗓 Твой график: " + outletName + ", " + r.from().format(DM) + "–" + r.to().format(DM),
                    body, null, null, null, p.id());
        });
        return Map.of("published", changed, "notified", byUser.size());
    }

    private void notifyRemoved(List<ScheduleSlot> removed, Long outletId, Long by) {
        if (removed.isEmpty()) return;
        String outletName = outletName(outletId);
        removed.stream().collect(Collectors.groupingBy(ScheduleSlot::getUserId)).forEach((userId, list) -> {
            String body = list.stream().sorted(SLOT_ORDER).map(ScheduleService::line).collect(Collectors.joining("\n"));
            notifications.send(userId, NotificationType.SCHEDULE, "🗓 Смена отменена: " + outletName, body,
                    null, null, null, by);
        });
    }

    // ================= мой график и пожелания =================

    @Transactional(readOnly = true)
    public List<MySlot> my(UserPrincipal p, LocalDate from, LocalDate to) {
        checkRange(from, to);
        Map<Long, String> names = outlets.findAll().stream().collect(Collectors.toMap(Outlet::getId, Outlet::getName));
        return slots.findByUserIdAndSlotDateBetweenAndPublishedTrueOrderBySlotDateAsc(p.id(), from, to).stream()
                .sorted(SLOT_ORDER)
                .map(s -> new MySlot(s.getSlotDate(), s.getDayPart(), s.getRole(), s.getStartTime(), s.getEndTime(),
                        s.getOutletId(), names.getOrDefault(s.getOutletId(), "?")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MySlot> today(UserPrincipal p) {
        LocalDate today = LocalDate.now(ZONE);
        return my(p, today, today);
    }

    @Transactional(readOnly = true)
    public List<Limit> myLimits(UserPrincipal p) {
        String name = users.findById(p.id()).map(AppUser::getFullName).orElse("?");
        return limits.findByUserIdOrderByIdAsc(p.id()).stream().map(l -> limitDto(l, name)).toList();
    }

    @Transactional
    public Limit addMyLimit(UserPrincipal p, LimitRequest r) {
        if (r.from() != null && r.to() != null && r.to().isBefore(r.from())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Дата «по» раньше даты «с»");
        }
        if (limits.findByUserIdOrderByIdAsc(p.id()).size() >= 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Слишком много правил. Удали старые");
        }
        StaffLimit l = new StaffLimit();
        l.setUserId(p.id());
        l.setWeekdays(r.weekdays().stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        l.setDayPart(r.dayPart());
        l.setDateFrom(r.from());
        l.setDateTo(r.to());
        l.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        limits.save(l);
        return limitDto(l, users.findById(p.id()).map(AppUser::getFullName).orElse("?"));
    }

    /** Удалить правило: своё — любой; чужое — директор этой точки или суперадмин. */
    @Transactional
    public void deleteLimit(UserPrincipal p, Long id) {
        StaffLimit l = limits.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Не найдено"));
        if (!l.getUserId().equals(p.id())) {
            if (p.role() != AccountRole.SUPER_ADMIN && p.role() != AccountRole.DIRECTOR) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Можно удалять только свои пожелания");
            }
            checkUser(p, users.findById(l.getUserId()).orElseThrow());
        }
        limits.delete(l);
    }

    // ================= сотрудники и отсутствия =================

    @Transactional
    public Staff updateStaff(UserPrincipal p, Long userId, StaffRequest r) {
        AppUser u = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Сотрудник не найден"));
        checkUser(p, u);
        StaffProfile sp = profiles.findById(userId).orElseGet(() -> {
            StaffProfile n = new StaffProfile();
            n.setUserId(userId);
            return n;
        });
        sp.setJobTitle(r.jobTitle());
        sp.setCanInside(r.jobTitle() != JobTitle.TRAINEE && r.canInside());
        sp.setSchedulable(r.schedulable());
        sp.setMaxShiftsWeek(r.maxShiftsWeek());
        if (!sp.isCanInside()) {
            LocalDate today = LocalDate.now(ZONE);
            release(slots.findByUserIdInAndSlotDateBetween(List.of(userId), today, today.plusDays(120)).stream()
                    .filter(s -> s.getRole() == ShiftRole.INSIDE).toList());
        }
        profiles.save(sp);
        return new Staff(u.getId(), u.getFullName(), u.getLogin(), sp.getJobTitle(), sp.isCanInside(),
                sp.isSchedulable(), sp.getMaxShiftsWeek());
    }

    @Transactional
    public Absence addAbsence(UserPrincipal p, AbsenceRequest r) {
        if (r.to().isBefore(r.from())) throw new ApiException(HttpStatus.BAD_REQUEST, "Дата «по» раньше даты «с»");
        AppUser u = users.findById(r.userId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Сотрудник не найден"));
        checkUser(p, u);
        StaffAbsence a = new StaffAbsence();
        a.setUserId(u.getId());
        a.setKind(r.kind());
        a.setDateFrom(r.from());
        a.setDateTo(r.to());
        a.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        a.setCreatedBy(p.id());
        absences.save(a);
        int freed = release(slots.findByUserIdInAndSlotDateBetween(List.of(u.getId()), r.from(), r.to()));
        return new Absence(a.getId(), u.getId(), u.getFullName(), a.getKind(), a.getDateFrom(), a.getDateTo(),
                a.getNote(), freed);
    }

    @Transactional
    public void deleteAbsence(UserPrincipal p, Long id) {
        StaffAbsence a = absences.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Не найдено"));
        checkUser(p, users.findById(a.getUserId()).orElseThrow());
        absences.delete(a);
    }

    // ================= сборка ответа =================

    private Board buildBoard(Long outletId, LocalDate from, LocalDate to) {
        List<Person> staff = staffOf(outletId);
        Map<Long, Person> byId = staff.stream().collect(Collectors.toMap(Person::id, Function.identity()));
        Map<Long, String> names = new HashMap<>();
        staff.forEach(s -> names.put(s.id(), s.name()));

        OutletStaffing st = staffing.findById(outletId).orElseGet(OutletStaffing::new);
        List<ShiftRole> morning = parse(st.getMorning());
        List<ShiftRole> evening = parse(st.getEvening());
        List<MiddleShift> middle = parseMiddle(st.getMiddle());
        List<Cell> plan = plan(morning, evening, middle);

        List<Long> ids = staff.stream().map(Person::id).toList();
        List<StaffAbsence> absRaw = ids.isEmpty() ? List.of() : absences.overlapping(ids, from, to);
        Map<Long, List<StaffLimit>> lim = limitsOf(ids);
        List<ScheduleSlot> list = slots.findByOutletIdAndSlotDateBetweenOrderBySlotDateAsc(outletId, from, to);

        List<String> warnings = new ArrayList<>();
        List<Slot> slotDtos = new ArrayList<>();
        for (ScheduleSlot s : list) {
            String name = s.getUserId() == null ? null : names.computeIfAbsent(s.getUserId(),
                    id -> users.findById(id).map(AppUser::getFullName).orElse("?"));
            String conflict = conflictOf(s, byId, absRaw);
            String limit = conflict == null ? limitOf(s, lim) : null;
            if (conflict != null) warnings.add(line(s) + ": " + name + " (" + conflict + ")");
            else if (limit != null) warnings.add(line(s) + ": " + name + " 🙅 " + limit);
            slotDtos.add(new Slot(s.getSlotDate(), s.getDayPart(), s.getRole(), s.getStartTime(), s.getEndTime(),
                    s.getUserId(), name, s.isPublished(), conflict, limit));
        }

        Set<String> filled = list.stream().filter(s -> s.getUserId() != null).map(ScheduleService::key)
                .collect(Collectors.toSet());
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            for (Cell c : plan) {
                if (!filled.contains(key(d, c.part(), c.role(), c.start()))) {
                    warnings.add(DOW[d.getDayOfWeek().getValue() - 1] + " " + d.format(DM) + " · "
                            + cellLabel(c) + ": некого поставить");
                }
            }
        }

        Map<Long, int[]> counts = new HashMap<>();
        for (ScheduleSlot s : list) {
            if (s.getUserId() == null) continue;
            int[] c = counts.computeIfAbsent(s.getUserId(), x -> new int[5]);
            c[0]++;
            switch (s.getDayPart()) {
                case MORNING -> c[1]++;
                case EVENING -> c[2]++;
                case MIDDLE -> c[3]++;
            }
            if (s.getRole() == ShiftRole.INSIDE) c[4]++;
        }
        List<Stat> stats = staff.stream().map(s -> {
            int[] c = counts.getOrDefault(s.id(), new int[5]);
            return new Stat(s.id(), c[0], c[1], c[2], c[3], c[4]);
        }).toList();

        List<Absence> abs = absRaw.stream()
                .map(a -> new Absence(a.getId(), a.getUserId(), names.get(a.getUserId()), a.getKind(),
                        a.getDateFrom(), a.getDateTo(), a.getNote(), 0))
                .toList();
        List<Limit> limitDtos = lim.values().stream().flatMap(List::stream)
                .filter(l -> l.getDateTo() == null || !l.getDateTo().isBefore(from))
                .sorted(Comparator.comparing(StaffLimit::getUserId).thenComparing(StaffLimit::getId))
                .map(l -> limitDto(l, names.get(l.getUserId())))
                .toList();
        List<Staff> staffDtos = staff.stream().map(s -> new Staff(s.id(), s.name(), s.login(), s.job(),
                s.canInside(), s.schedulable(), s.maxWeek())).toList();

        return new Board(outletId, outletName(outletId), from, to, morning, evening, middle, slotDtos, staffDtos,
                abs, limitDtos, stats, warnings.size() > 80 ? warnings.subList(0, 80) : warnings);
    }

    private List<Person> staffOf(Long outletId) {
        List<AppUser> list = users.findAll().stream()
                .filter(AppUser::isActive)
                .filter(u -> u.getAccountRole() != AccountRole.SUPER_ADMIN)
                .filter(u -> u.getOutlets().stream().anyMatch(o -> o.getId().equals(outletId)))
                .sorted(Comparator.comparing(AppUser::getFullName))
                .toList();
        Map<Long, StaffProfile> prof = profiles.findAllById(list.stream().map(AppUser::getId).toList()).stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        return list.stream().map(u -> {
            StaffProfile sp = prof.get(u.getId());
            boolean director = u.getAccountRole() == AccountRole.DIRECTOR;
            if (sp == null) {
                return new Person(u.getId(), u.getFullName(), u.getLogin(),
                        director ? JobTitle.DIRECTOR : JobTitle.MANAGER, true, !director, 5);
            }
            return new Person(u.getId(), u.getFullName(), u.getLogin(), sp.getJobTitle(), sp.isCanInside(),
                    sp.isSchedulable(), sp.getMaxShiftsWeek());
        }).toList();
    }

    // ================= правила и подписи =================

    /** Нельзя стоять в этой клетке (или null). */
    private static String conflictOf(ScheduleSlot s, Map<Long, Person> byId, List<StaffAbsence> abs) {
        if (s.getUserId() == null) return null;
        for (StaffAbsence a : abs) {
            if (a.getUserId().equals(s.getUserId())
                    && !s.getSlotDate().isBefore(a.getDateFrom()) && !s.getSlotDate().isAfter(a.getDateTo())) {
                return KIND.get(a.getKind());
            }
        }
        Person x = byId.get(s.getUserId());
        if (x == null) return "не закреплён за точкой";
        if (s.getRole() == ShiftRole.INSIDE && !x.canInside()) return "не ставится инсайдом";
        if (s.getRole() == ShiftRole.INSIDE && s.getDayPart() == DayPart.MIDDLE) return "у инсайда нет промежа";
        return null;
    }

    /** Человек просил не ставить сюда (или null). */
    private static String limitOf(ScheduleSlot s, Map<Long, List<StaffLimit>> lim) {
        if (s.getUserId() == null) return null;
        return lim.getOrDefault(s.getUserId(), List.of()).stream()
                .filter(l -> l.blocks(s.getSlotDate(), s.getDayPart()))
                .findFirst()
                .map(l -> "просил не ставить: " + describe(l))
                .orElse(null);
    }

    private static boolean limited(Map<Long, List<StaffLimit>> lim, Long userId, LocalDate d, DayPart part) {
        return lim.getOrDefault(userId, List.of()).stream().anyMatch(l -> l.blocks(d, part));
    }

    private Map<Long, List<StaffLimit>> limitsOf(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return limits.findByUserIdIn(ids).stream().collect(Collectors.groupingBy(StaffLimit::getUserId));
    }

    /** «пн, вт · утро · до 30.11 (учёба)». */
    private static String describe(StaffLimit l) {
        Set<Integer> days = l.days();
        String d = days.size() == 7 ? "все дни"
                : days.stream().sorted().map(n -> DOW_SHORT[n - 1]).collect(Collectors.joining(", "));
        String part = l.getDayPart() == null ? "весь день" : partLabel(l.getDayPart());
        String period = l.getDateFrom() == null && l.getDateTo() == null ? ""
                : " · " + (l.getDateFrom() == null ? "" : "с " + l.getDateFrom().format(DM) + " ")
                + (l.getDateTo() == null ? "" : "по " + l.getDateTo().format(DM));
        return d + " · " + part + period.stripTrailing() + (l.getNote() == null ? "" : " (" + l.getNote() + ")");
    }

    private static Limit limitDto(StaffLimit l, String name) {
        return new Limit(l.getId(), l.getUserId(), name, l.days().stream().sorted().toList(), l.getDayPart(),
                l.getDateFrom(), l.getDateTo(), l.getNote());
    }

    /** Промежи: только кухня и прилавок, 10:00–23:00, без дублей. */
    private static List<MiddleShift> checkMiddle(List<MiddleShift> list) {
        if (list == null || list.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        for (MiddleShift m : list) {
            if (m.role() == ShiftRole.INSIDE) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Промеж бывает только у кухни и прилавка");
            }
            if (m.start().isBefore(MIDDLE_FROM)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Промеж начинается не раньше 10:00 (раньше это утро)");
            }
            if (m.end().isAfter(MIDDLE_TO)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Промеж заканчивается не позже 23:00 (до нулей это вечер)");
            }
            if (!m.end().isAfter(m.start())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "У промежа «до» должно быть позже «с»");
            }
            if (!seen.add(m.role() + "@" + m.start())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Два промежа " + ROLE.get(m.role()) + " с " + m.start().format(HM) + ": поменяй время начала");
            }
        }
        return list.stream().sorted(Comparator.comparing(MiddleShift::start).thenComparing(MiddleShift::role)).toList();
    }

    private static Cell cellOf(DayPart part, ShiftRole role, LocalTime start, LocalTime end) {
        if (part != DayPart.MIDDLE) return new Cell(part, role, null, null);
        if (start == null || end == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "У промежа должно быть время «с» и «до»");
        }
        MiddleShift m = checkMiddle(List.of(new MiddleShift(role, start, end))).get(0);
        return new Cell(DayPart.MIDDLE, m.role(), m.start(), m.end());
    }

    private static List<Cell> plan(List<ShiftRole> morning, List<ShiftRole> evening, List<MiddleShift> middle) {
        List<Cell> cells = new ArrayList<>();
        morning.forEach(r -> cells.add(new Cell(DayPart.MORNING, r, null, null)));
        evening.forEach(r -> cells.add(new Cell(DayPart.EVENING, r, null, null)));
        middle.forEach(m -> cells.add(new Cell(DayPart.MIDDLE, m.role(), m.start(), m.end())));
        return cells;
    }

    private Optional<ScheduleSlot> findSlot(Long outletId, LocalDate d, Cell c) {
        return slots.findByOutletIdAndSlotDateAndDayPartAndRoleAndStartTime(outletId, d, c.part(), c.role(), c.start());
    }

    private static ScheduleSlot newSlot(Long outletId, LocalDate d, Cell c) {
        ScheduleSlot s = new ScheduleSlot();
        s.setOutletId(outletId);
        s.setSlotDate(d);
        s.setDayPart(c.part());
        s.setRole(c.role());
        s.setStartTime(c.start());
        s.setEndTime(c.end());
        return s;
    }

    private static final Comparator<ScheduleSlot> SLOT_ORDER = Comparator.comparing(ScheduleSlot::getSlotDate)
            .thenComparing(ScheduleSlot::getDayPart)
            .thenComparing(ScheduleSlot::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder()));

    /** «Пн 06.10 · 🌤 Промеж 12:00–21:00 · Прилавок». */
    private static String line(ScheduleSlot s) {
        return DOW[s.getSlotDate().getDayOfWeek().getValue() - 1] + " " + s.getSlotDate().format(DM) + " · "
                + s.getDayPart().label() + times(s.getDayPart(), s.getStartTime(), s.getEndTime())
                + " · " + ROLE.get(s.getRole());
    }

    private static String cellLabel(ScheduleSlot s) {
        return partLabel(s.getDayPart()) + times(s.getDayPart(), s.getStartTime(), s.getEndTime()) + " · " + ROLE.get(s.getRole());
    }

    private static String cellLabel(Cell c) {
        return partLabel(c.part()) + times(c.part(), c.start(), c.end()) + " · " + ROLE.get(c.role());
    }

    private static String times(DayPart part, LocalTime start, LocalTime end) {
        return part == DayPart.MIDDLE && start != null && end != null
                ? " " + start.format(HM) + "–" + end.format(HM) : "";
    }

    private static String partLabel(DayPart part) {
        return switch (part) {
            case MORNING -> "утро";
            case EVENING -> "вечер";
            case MIDDLE -> "промеж";
        };
    }

    private static String partKey(DayPart part, ShiftRole role, LocalTime start) {
        return part + "|" + role + "|" + (part == DayPart.MIDDLE ? start : "");
    }

    private static String key(LocalDate d, DayPart part, ShiftRole role, LocalTime start) {
        return d + "|" + partKey(part, role, start);
    }

    private static String key(ScheduleSlot s) {
        return key(s.getSlotDate(), s.getDayPart(), s.getRole(), s.getStartTime());
    }

    private int release(List<ScheduleSlot> list) {
        OffsetDateTime now = OffsetDateTime.now();
        int n = 0;
        for (ScheduleSlot s : list) {
            if (s.getUserId() == null) continue;
            s.setUserId(null);
            s.setPublished(false);
            s.setUpdatedAt(now);
            n++;
        }
        return n;
    }

    private static Set<Long> ignoring(ScheduleSlot... list) {
        Set<Long> set = new HashSet<>();
        for (ScheduleSlot s : list) if (s.getId() != null) set.add(s.getId());
        return set;
    }

    private static boolean absentOn(List<StaffAbsence> abs, Long userId, LocalDate d) {
        return abs.stream().anyMatch(a -> a.getUserId().equals(userId)
                && !d.isBefore(a.getDateFrom()) && !d.isAfter(a.getDateTo()));
    }

    private static List<ShiftRole> ordered(Collection<ShiftRole> roles) {
        return ORDER.stream().filter(roles::contains).toList();
    }

    private static List<ShiftRole> parse(String s) {
        if (s == null || s.isBlank()) return List.of();
        Set<ShiftRole> set = Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .map(ShiftRole::valueOf).collect(Collectors.toSet());
        return ordered(set);
    }

    private static String join(List<ShiftRole> roles) {
        return roles.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static String formatMiddle(List<MiddleShift> list) {
        return list.stream().map(m -> m.role() + "@" + m.start().format(HM) + "-" + m.end().format(HM))
                .collect(Collectors.joining(";"));
    }

    private static List<MiddleShift> parseMiddle(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<MiddleShift> out = new ArrayList<>();
        for (String part : s.split(";")) {
            try {
                String[] rt = part.trim().split("@");
                String[] t = rt[1].split("-");
                out.add(new MiddleShift(ShiftRole.valueOf(rt[0]), LocalTime.parse(t[0]), LocalTime.parse(t[1])));
            } catch (Exception ignored) {
                // битая запись в штатке → пропускаем
            }
        }
        return out;
    }

    private String outletName(Long id) {
        return outlets.findById(id).map(Outlet::getName).orElse("?");
    }

    private void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "Дата «по» раньше даты «с»");
        if (ChronoUnit.DAYS.between(from, to) > 62) throw new ApiException(HttpStatus.BAD_REQUEST, "Период не больше двух месяцев");
    }

    private void checkOutlet(UserPrincipal p, Long outletId) {
        if (p.role() == AccountRole.SUPER_ADMIN) return;
        boolean mine = users.findById(p.id())
                .map(u -> u.getOutlets().stream().anyMatch(o -> o.getId().equals(outletId))).orElse(false);
        if (!mine) throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой точке");
    }

    private void checkUser(UserPrincipal p, AppUser target) {
        if (p.role() == AccountRole.SUPER_ADMIN) return;
        Set<Long> mine = users.findById(p.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet())).orElse(Set.of());
        if (target.getOutlets().stream().noneMatch(o -> mine.contains(o.getId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этому сотруднику");
        }
    }
}