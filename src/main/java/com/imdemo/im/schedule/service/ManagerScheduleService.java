package com.imdemo.im.schedule.service;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.AppUser;
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

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerScheduleService {

    private final ManagerWeekRepository weekRepo;
    private final ManagerScheduleRepository entryRepo;
    private final StaffProfileRepository staffRepo;
    private final UserRepository userRepo;

    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm");

    public List<ManagerWeekDto> weeks(UserPrincipal p, Long outletId) {
        checkAccess(p, outletId);
        return weekRepo.findByOutletIdOrderByWeekStartDesc(outletId)
                .stream().map(this::toWeekDto).toList();
    }

    public ManagerBoardDto board(UserPrincipal p, Long weekId) {
        ManagerWeek week = weekRepo.findById(weekId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Неделя не найдена"));
        checkAccess(p, week.getOutletId());
        List<ManagerScheduleEntry> entries = entryRepo.findByWeekId(weekId);
        Map<Long, AppUser> users = loadUsers(week.getOutletId());
        return new ManagerBoardDto(
                toWeekDto(week),
                entries.stream().map(e -> toEntryDto(e, users)).toList(),
                toStaffDtos(week.getOutletId(), users)
        );
    }

    @Transactional
    public ManagerWeekDto createWeek(UserPrincipal p, CreateWeekRequest r) {
        checkAccess(p, r.outletId());
        weekRepo.findByOutletIdAndWeekStart(r.outletId(), r.weekStart())
                .ifPresent(w -> { throw new ApiException(HttpStatus.CONFLICT, "Лист за эту неделю уже существует"); });
        ManagerWeek week = new ManagerWeek();
        week.setOutletId(r.outletId());
        week.setWeekStart(r.weekStart());
        week.setTitle(r.title());
        week = weekRepo.save(week);
        if (r.copyFromWeekId() != null) {
            final Long newWeekId = week.getId();
            final int daysDiff = (int) (r.weekStart().toEpochDay()
                    - weekRepo.findById(r.copyFromWeekId()).orElseThrow().getWeekStart().toEpochDay());
            for (ManagerScheduleEntry src : entryRepo.findByWeekId(r.copyFromWeekId())) {
                ManagerScheduleEntry copy = new ManagerScheduleEntry();
                copy.setWeekId(newWeekId);
                copy.setOutletId(src.getOutletId());
                copy.setUserId(src.getUserId());
                copy.setSchedDate(src.getSchedDate().plusDays(daysDiff));
                copy.setShiftStart(src.getShiftStart());
                copy.setShiftEnd(src.getShiftEnd());
                copy.setDayOff(src.isDayOff());
                copy.setComment(null);
                entryRepo.save(copy);
            }
        }
        return toWeekDto(week);
    }

    @Transactional
    public ManagerEntryDto saveEntry(UserPrincipal p, SaveEntryRequest r) {
        checkAccess(p, r.outletId());
        weekRepo.findById(r.weekId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Неделя не найдена"));
        ManagerScheduleEntry entry = entryRepo.findByWeekId(r.weekId()).stream()
                .filter(e -> e.getUserId().equals(r.userId()) && e.getSchedDate().equals(r.date()))
                .findFirst()
                .orElseGet(ManagerScheduleEntry::new);
        entry.setWeekId(r.weekId());
        entry.setOutletId(r.outletId());
        entry.setUserId(r.userId());
        entry.setSchedDate(r.date());
        entry.setShiftStart(r.shiftStart() != null ? LocalTime.parse(r.shiftStart(), T) : null);
        entry.setShiftEnd(r.shiftEnd() != null ? LocalTime.parse(r.shiftEnd(), T) : null);
        entry.setDayOff(r.dayOff());
        entry.setComment(r.comment());
        entry.setUpdatedAt(OffsetDateTime.now());
        entry = entryRepo.save(entry);
        Map<Long, AppUser> users = loadUsers(r.outletId());
        return toEntryDto(entry, users);
    }

    @Transactional
    public void deleteWeek(UserPrincipal p, Long weekId) {
        ManagerWeek week = weekRepo.findById(weekId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Неделя не найдена"));
        checkAccess(p, week.getOutletId());
        weekRepo.delete(week);
    }

    private void checkAccess(UserPrincipal p, Long outletId) {
        if (p.role() == AccountRole.SUPER_ADMIN) return;
        boolean ok = userRepo.findById(p.id())
                .map(u -> u.getOutlets().stream().anyMatch(o -> o.getId().equals(outletId)))
                .orElse(false);
        if (!ok) throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой точке");
    }

    private Map<Long, AppUser> loadUsers(Long outletId) {
        return userRepo.findAll().stream()
                .filter(AppUser::isActive)
                .filter(u -> u.getOutlets().stream().anyMatch(o -> o.getId().equals(outletId)))
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private List<Staff> toStaffDtos(Long outletId, Map<Long, AppUser> users) {
        Map<Long, StaffProfile> profiles = staffRepo
                .findAllById(users.keySet()).stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        return users.values().stream()
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(u -> {
                    StaffProfile sp = profiles.get(u.getId());
                    boolean director = u.getAccountRole() != null &&
                            u.getAccountRole().name().equals("DIRECTOR");
                    return new Staff(
                            u.getId(), u.getFullName(), u.getLogin(),
                            sp != null ? sp.getJobTitle() : (director ? JobTitle.DIRECTOR : JobTitle.MANAGER),
                            sp != null && sp.isCanInside(),
                            sp != null ? sp.isSchedulable() : !director,
                            sp != null ? sp.getMaxShiftsWeek() : 5
                    );
                }).toList();
    }

    private ManagerWeekDto toWeekDto(ManagerWeek w) {
        return new ManagerWeekDto(w.getId(), w.getWeekStart(), w.getTitle());
    }

    private ManagerEntryDto toEntryDto(ManagerScheduleEntry e, Map<Long, AppUser> users) {
        AppUser u = users.get(e.getUserId());
        String name = u != null ? u.getFullName() : "?";
        StaffProfile sp = u != null ? staffRepo.findById(u.getId()).orElse(null) : null;
        String job = sp != null ? sp.getJobTitle().name() : "";
        return new ManagerEntryDto(
                e.getId(), e.getUserId(), name, job, e.getSchedDate(),
                e.getShiftStart() != null ? e.getShiftStart().format(T) : null,
                e.getShiftEnd() != null ? e.getShiftEnd().format(T) : null,
                e.isDayOff(), e.getComment()
        );
    }
}
