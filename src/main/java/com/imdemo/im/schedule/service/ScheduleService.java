package com.imdemo.im.schedule.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.notify.domain.NotificationType;
import com.imdemo.im.notify.service.NotificationService;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.schedule.domain.*;
import com.imdemo.im.schedule.dto.ScheduleDto.*;
import com.imdemo.im.schedule.repository.OutletStaffingRepository;
import com.imdemo.im.schedule.repository.ScheduleSlotRepository;
import com.imdemo.im.schedule.repository.StaffAbsenceRepository;
import com.imdemo.im.schedule.repository.StaffProfileRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private static final List<DayPart> PARTS = List.of(DayPart.MORNING, DayPart.EVENING);
    private static final int MAX_IN_ROW = 5;
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final String[] DOW = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final Map<ShiftRole, String> ROLE = Map.of(
            ShiftRole.INSIDE, "Инсайд", ShiftRole.PRODUCTION_MANAGER, "Кухня", ShiftRole.SERVICE_MANAGER, "Прилавок");
    private static final Map<AbsenceKind, String> KIND = Map.of(
            AbsenceKind.VACATION, "отпуск", AbsenceKind.SICK, "больничный", AbsenceKind.OTHER, "отсутствует");

    private final ScheduleSlotRepository slots;
    private final StaffProfileRepository profiles;
    private final StaffAbsenceRepository absences;
    private final OutletStaffingRepository staffing;
    private final UserRepository users;
    private final OutletRepository outlets;
    private final NotificationService notifications;

    /** Сотрудник точки с настройками для графика. */
    private record Person(Long id, String name, String login, JobTitle job, boolean canInside,
                          boolean schedulable, int maxWeek) {}

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

        OutletStaffing st = staffing.findById(outletId).orElseGet(() -> {
            OutletStaffing n = new OutletStaffing();
            n.setOutletId(outletId);
            return n;
        });
        st.setMorning(join(morning));
        st.setEvening(join(evening));
        staffing.save(st);

        List<Person> staff = staffOf(outletId);
        Map<Long, Person> byId = staff.stream().collect(Collectors.toMap(Person::id, Function.identity()));
        List<Person> pool = staff.stream().filter(Person::schedulable).toList();
        OffsetDateTime now = OffsetDateTime.now();

        // 1. существующие клетки окна: лишние роли убрать, черновик очистить (если не «оставлять заполненное»)
        Map<String, ScheduleSlot> grid = new HashMap<>();
        for (ScheduleSlot s : slots.findByOutletIdAndSlotDateBetweenOrderBySlotDateAsc(outletId, from, to)) {
            boolean planned = (s.getDayPart() == DayPart.MORNING ? morning : evening).contains(s.getRole());
            if (!s.isPublished() && !planned) {
                slots.delete(s);
                continue;
            }
            if (!s.isPublished() && !r.keepFilled()) s.setUserId(null);
            grid.put(key(s.getSlotDate(), s.getDayPart(), s.getRole()), s);
        }
        slots.flush();

        // 2. занятость людей: неделя до окна + само окно, все точки
        Busy busy = new Busy();
        List<Long> ids = staff.stream().map(Person::id).toList();
        if (!ids.isEmpty()) {
            for (ScheduleSlot s : slots.findByUserIdInAndSlotDateBetween(ids, from.minusDays(7), to)) {
                if (s.getUserId() != null) {
                    busy.add(s.getUserId(), s.getSlotDate(), s.getDayPart(), s.getRole(), !s.getSlotDate().isBefore(from));
                }
            }
        }
        List<StaffAbsence> abs = ids.isEmpty() ? List.of() : absences.overlapping(ids, from, to);

        // 3. день за днём: утро → вечер, внутри смены сначала инсайд
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate day = d;
            for (DayPart part : PARTS) {
                for (ShiftRole role : part == DayPart.MORNING ? morning : evening) {
                    ScheduleSlot slot = grid.computeIfAbsent(key(day, part, role), k -> newSlot(outletId, day, part, role));
                    if (slot.getUserId() != null) continue;
                    Person pick = pick(pool, busy, abs, day, part, role, grid, byId);
                    if (pick == null) continue; // останется «⚠ пусто»
                    slot.setUserId(pick.id());
                    slot.setPublished(false);
                    slot.setUpdatedAt(now);
                    busy.add(pick.id(), day, part, role, true);
                }
            }
        }
        slots.saveAll(grid.values());
        return buildBoard(outletId, from, to);
    }

    private Person pick(List<Person> pool, Busy busy, List<StaffAbsence> abs, LocalDate d, DayPart part,
                        ShiftRole role, Map<String, ScheduleSlot> grid, Map<Long, Person> byId) {
        boolean partHasLead = ORDER.stream().anyMatch(r2 -> {
            ScheduleSlot s = grid.get(key(d, part, r2));
            Person x = s == null || s.getUserId() == null ? null : byId.get(s.getUserId());
            return x != null && x.job() != JobTitle.TRAINEE;
        });
        return pool.stream()
                .filter(x -> !absentOn(abs, x.id(), d))
                .filter(x -> !busy.worksOn(x.id(), d))
                .filter(x -> role != ShiftRole.INSIDE || x.canInside())
                .filter(x -> x.job() != JobTitle.TRAINEE || partHasLead)
                .filter(x -> !(part == DayPart.MORNING && busy.partOn(x.id(), d.minusDays(1)) == DayPart.EVENING))
                .filter(x -> !(part == DayPart.EVENING && busy.partOn(x.id(), d.plusDays(1)) == DayPart.MORNING))
                .filter(x -> busy.week(x.id(), d) < x.maxWeek())
                .filter(x -> busy.streak(x.id(), d) < MAX_IN_ROW)
                .min(Comparator.comparingDouble(x -> cost(x, busy, d, part, role)))
                .orElse(null);
    }

    /** Чем меньше, тем лучше кандидат. */
    private static double cost(Person x, Busy b, LocalDate d, DayPart part, ShiftRole role) {
        int[] w = b.window(x.id()); // [смен, утро, вечер, инсайд] в окне
        double c = 10.0 * b.week(x.id(), d) + 3.0 * w[0];
        int bias = part == DayPart.MORNING ? w[1] - w[2] : w[2] - w[1];
        c += 2.0 * Math.max(0, bias);                 // утро и вечер поровну
        if (role == ShiftRole.INSIDE) c += 2.0 * w[3]; // инсайды по очереди
        else if (x.canInside()) c += 4.0;              // беречь тех, кто может инсайдом
        c += b.streak(x.id(), d);                      // меньше дней подряд
        if (x.job() == JobTitle.DIRECTOR) c += 100;    // директор в последнюю очередь
        c += Math.floorMod(x.id() * 31 + d.toEpochDay(), 7) * 0.1; // разнообразие при равенстве
        return c;
    }

    /** Кто когда работает: для правил и подсчёта. */
    private static final class Busy {
        private final Map<Long, Map<LocalDate, DayPart>> days = new HashMap<>();
        private final Map<Long, int[]> window = new HashMap<>();

        void add(Long u, LocalDate d, DayPart part, ShiftRole role, boolean inWindow) {
            days.computeIfAbsent(u, x -> new HashMap<>()).put(d, part);
            if (inWindow) {
                int[] w = window.computeIfAbsent(u, x -> new int[4]);
                w[0]++;
                if (part == DayPart.MORNING) w[1]++;
                else w[2]++;
                if (role == ShiftRole.INSIDE) w[3]++;
            }
        }

        boolean worksOn(Long u, LocalDate d) {
            return days.getOrDefault(u, Map.of()).containsKey(d);
        }

        DayPart partOn(Long u, LocalDate d) {
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
            return window.getOrDefault(u, new int[4]);
        }
    }

    // ================= ручная правка =================

    @Transactional
    public Result setSlot(UserPrincipal p, SlotRequest r) {
        checkOutlet(p, r.outletId());
        ScheduleSlot slot = slots.findByOutletIdAndSlotDateAndDayPartAndRole(r.outletId(), r.date(), r.dayPart(), r.role())
                .orElseGet(() -> slots.saveAndFlush(newSlot(r.outletId(), r.date(), r.dayPart(), r.role())));
        List<String> warns = r.userId() == null ? List.of()
                : validate(r.outletId(), r.userId(), r.date(), r.dayPart(), r.role(), ignoring(slot));
        slot.setUserId(r.userId());
        slot.setPublished(false); // после правки нужно опубликовать заново
        slot.setUpdatedAt(OffsetDateTime.now());
        return new Result(warns);
    }

    /** Обмен двух клеток (перетаскивание). */
    @Transactional
    public Result swap(UserPrincipal p, SwapRequest r) {
        checkOutlet(p, r.outletId());
        ScheduleSlot a = slotFor(r.outletId(), r.a());
        ScheduleSlot b = slotFor(r.outletId(), r.b());
        if (a.getId().equals(b.getId())) return new Result(List.of());
        Long ua = a.getUserId();
        Long ub = b.getUserId();
        Set<Long> ignore = ignoring(a, b);
        List<String> warns = new ArrayList<>();
        if (ub != null) warns.addAll(validate(r.outletId(), ub, a.getSlotDate(), a.getDayPart(), a.getRole(), ignore));
        if (ua != null) warns.addAll(validate(r.outletId(), ua, b.getSlotDate(), b.getDayPart(), b.getRole(), ignore));
        OffsetDateTime now = OffsetDateTime.now();
        a.setUserId(ub);
        b.setUserId(ua);
        a.setPublished(false);
        b.setPublished(false);
        a.setUpdatedAt(now);
        b.setUpdatedAt(now);
        return new Result(warns);
    }

    /** Жёсткие правила → ошибка; рискованное → предупреждение. */
    private List<String> validate(Long outletId, Long userId, LocalDate date, DayPart part, ShiftRole role, Set<Long> ignore) {
        Person x = staffOf(outletId).stream().filter(s -> s.id().equals(userId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Сотрудник не закреплён за этой точкой"));
        if (role == ShiftRole.INSIDE && !x.canInside()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    x.name() + " не ставится инсайдом. Это меняется во вкладке «Сотрудники»");
        }
        absences.overlapping(List.of(userId), date, date).stream().findFirst().ifPresent(a -> {
            throw new ApiException(HttpStatus.BAD_REQUEST, x.name() + ": " + KIND.get(a.getKind()) + " "
                    + a.getDateFrom().format(DM) + "–" + a.getDateTo().format(DM));
        });

        List<String> warns = new ArrayList<>();
        int week = 0;
        LocalDate monday = date.with(DayOfWeek.MONDAY);
        for (ScheduleSlot s : slots.findByUserIdInAndSlotDateBetween(List.of(userId), monday.minusDays(1), monday.plusDays(7))) {
            if (ignore.contains(s.getId())) continue;
            LocalDate sd = s.getSlotDate();
            if (sd.equals(date)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, x.name() + " уже стоит в этот день: "
                        + partLabel(s.getDayPart()) + " · " + ROLE.get(s.getRole()));
            }
            if (part == DayPart.MORNING && sd.equals(date.minusDays(1)) && s.getDayPart() == DayPart.EVENING) {
                warns.add(x.name() + ": вчера вечер, сегодня утро (закрыл — открыл)");
            }
            if (part == DayPart.EVENING && sd.equals(date.plusDays(1)) && s.getDayPart() == DayPart.MORNING) {
                warns.add(x.name() + ": сегодня вечер, завтра утро (закрыл — открыл)");
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
            String body = mine.stream()
                    .sorted(Comparator.comparing(ScheduleSlot::getSlotDate).thenComparing(ScheduleSlot::getDayPart))
                    .map(s -> DOW[s.getSlotDate().getDayOfWeek().getValue() - 1] + " " + s.getSlotDate().format(DM)
                            + " · " + (s.getDayPart() == DayPart.MORNING ? "🌅 Утро" : "🌙 Вечер")
                            + " · " + ROLE.get(s.getRole()))
                    .collect(Collectors.joining("\n"));
            notifications.send(userId, NotificationType.SCHEDULE,
                    "🗓 Твой график: " + outletName + ", " + r.from().format(DM) + "–" + r.to().format(DM),
                    body, null, null, null, p.id());
        });
        return Map.of("published", changed, "notified", byUser.size());
    }

    // ================= мой график =================

    @Transactional(readOnly = true)
    public List<MySlot> my(UserPrincipal p, LocalDate from, LocalDate to) {
        checkRange(from, to);
        Map<Long, String> names = outlets.findAll().stream().collect(Collectors.toMap(Outlet::getId, Outlet::getName));
        return slots.findByUserIdAndSlotDateBetweenAndPublishedTrueOrderBySlotDateAsc(p.id(), from, to).stream()
                .sorted(Comparator.comparing(ScheduleSlot::getSlotDate).thenComparing(ScheduleSlot::getDayPart))
                .map(s -> new MySlot(s.getSlotDate(), s.getDayPart(), s.getRole(), s.getOutletId(),
                        names.getOrDefault(s.getOutletId(), "?")))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MySlot> today(UserPrincipal p) {
        LocalDate today = LocalDate.now(ZONE);
        return my(p, today, today);
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
        sp.setCanInside(r.jobTitle() != JobTitle.TRAINEE && r.canInside()); // стажёр не может быть инсайдом
        sp.setSchedulable(r.schedulable());
        sp.setMaxShiftsWeek(r.maxShiftsWeek());
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
        return new Absence(a.getId(), u.getId(), u.getFullName(), a.getKind(), a.getDateFrom(), a.getDateTo(), a.getNote());
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
        Map<Long, String> names = new HashMap<>();
        staff.forEach(s -> names.put(s.id(), s.name()));

        OutletStaffing st = staffing.findById(outletId).orElseGet(OutletStaffing::new);
        List<ShiftRole> morning = parse(st.getMorning());
        List<ShiftRole> evening = parse(st.getEvening());

        List<ScheduleSlot> list = slots.findByOutletIdAndSlotDateBetweenOrderBySlotDateAsc(outletId, from, to);
        List<Slot> slotDtos = list.stream().map(s -> new Slot(s.getSlotDate(), s.getDayPart(), s.getRole(), s.getUserId(),
                s.getUserId() == null ? null : names.computeIfAbsent(s.getUserId(),
                        id -> users.findById(id).map(AppUser::getFullName).orElse("?")),
                s.isPublished())).toList();

        List<Long> ids = staff.stream().map(Person::id).toList();
        List<Absence> abs = ids.isEmpty() ? List.of() : absences.overlapping(ids, from, to).stream()
                .map(a -> new Absence(a.getId(), a.getUserId(), names.get(a.getUserId()), a.getKind(),
                        a.getDateFrom(), a.getDateTo(), a.getNote()))
                .toList();

        Map<Long, int[]> counts = new HashMap<>();
        for (ScheduleSlot s : list) {
            if (s.getUserId() == null) continue;
            int[] c = counts.computeIfAbsent(s.getUserId(), x -> new int[4]);
            c[0]++;
            if (s.getDayPart() == DayPart.MORNING) c[1]++;
            else c[2]++;
            if (s.getRole() == ShiftRole.INSIDE) c[3]++;
        }
        List<Stat> stats = staff.stream().map(s -> {
            int[] c = counts.getOrDefault(s.id(), new int[4]);
            return new Stat(s.id(), c[0], c[1], c[2], c[3]);
        }).toList();

        // пустые клетки по штатке
        Set<String> filled = list.stream().filter(s -> s.getUserId() != null)
                .map(s -> key(s.getSlotDate(), s.getDayPart(), s.getRole())).collect(Collectors.toSet());
        List<String> warnings = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            for (DayPart part : PARTS) {
                for (ShiftRole role : part == DayPart.MORNING ? morning : evening) {
                    if (!filled.contains(key(d, part, role))) {
                        warnings.add(DOW[d.getDayOfWeek().getValue() - 1] + " " + d.format(DM) + " · "
                                + partLabel(part) + " · " + ROLE.get(role) + ": некого поставить");
                    }
                }
            }
        }

        List<Staff> staffDtos = staff.stream().map(s -> new Staff(s.id(), s.name(), s.login(), s.job(),
                s.canInside(), s.schedulable(), s.maxWeek())).toList();
        return new Board(outletId, outletName(outletId), from, to, morning, evening, slotDtos, staffDtos, abs, stats,
                warnings.size() > 40 ? warnings.subList(0, 40) : warnings);
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

    // ================= мелочи =================

    private ScheduleSlot slotFor(Long outletId, SlotRef ref) {
        return slots.findByOutletIdAndSlotDateAndDayPartAndRole(outletId, ref.date(), ref.dayPart(), ref.role())
                .orElseGet(() -> slots.saveAndFlush(newSlot(outletId, ref.date(), ref.dayPart(), ref.role())));
    }

    private static ScheduleSlot newSlot(Long outletId, LocalDate d, DayPart part, ShiftRole role) {
        ScheduleSlot s = new ScheduleSlot();
        s.setOutletId(outletId);
        s.setSlotDate(d);
        s.setDayPart(part);
        s.setRole(role);
        return s;
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

    private static String key(LocalDate d, DayPart part, ShiftRole role) {
        return d + "|" + part + "|" + role;
    }

    private static List<ShiftRole> ordered(Collection<ShiftRole> roles) {
        return ORDER.stream().filter(roles::contains).toList();
    }

    private static List<ShiftRole> parse(String s) {
        if (s == null || s.isBlank()) return List.of();
        Set<ShiftRole> set = Arrays.stream(s.split(",")).map(String::trim).map(ShiftRole::valueOf).collect(Collectors.toSet());
        return ordered(set);
    }

    private static String join(List<ShiftRole> roles) {
        return roles.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static String partLabel(DayPart part) {
        return part == DayPart.MORNING ? "утро" : "вечер";
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