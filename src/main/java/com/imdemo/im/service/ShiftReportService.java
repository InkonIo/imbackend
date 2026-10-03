package com.imdemo.im.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.repo.ChecklistRunRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    private static final double TOO_FAST = 0.3;  // меньше 30% нормы
    private static final double TOO_SLOW = 1.5;  // больше 150% нормы
    private static final Set<String> SERIOUS = Set.of("TOO_FAST", "LATE", "SKIPPED", "NOT_DONE");

    private final ShiftSessionRepository shifts;
    private final ChecklistRunRepository runs;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public List<ShiftSummaryDto> list(UserPrincipal viewer, LocalDate date) {
        Set<Long> scope = scope(viewer);
        List<ShiftSession> list;
        if (scope == null) list = shifts.findByShiftDateOrderByStartedAtAsc(date);
        else if (scope.isEmpty()) list = List.of();
        else list = shifts.findByShiftDateAndOutletIdInOrderByStartedAtAsc(date, scope);
        return list.stream().map(s -> summary(s, items(s))).toList();
    }

    @Transactional(readOnly = true)
    public ShiftReportDto report(UserPrincipal viewer, Long shiftId) {
        ShiftSession s = shifts.findById(shiftId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Смена не найдена"));
        Set<Long> scope = scope(viewer);
        if (scope != null && !scope.contains(s.getOutlet().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой точке");
        }
        List<ItemReportDto> items = items(s);
        return new ShiftReportDto(summary(s, items), items);
    }

    // ---------- расчёт ----------

    private List<ItemReportDto> items(ShiftSession s) {
        return runs.findByShiftId(s.getId())
                .map(run -> run.getItems().stream().map(ri -> item(s, ri)).toList())
                .orElse(List.of());
    }

    private ItemReportDto item(ShiftSession s, ChecklistRunItem ri) {
        List<String> flags = new ArrayList<>();

        Integer actual = null;
        if (ri.getStartedAt() != null && ri.getDoneAt() != null) {
            actual = (int) Duration.between(ri.getStartedAt(), ri.getDoneAt()).toMinutes();
            Integer norm = ri.getDurationMin();
            if (norm != null && ri.getStatus() == RunItemStatus.DONE) {
                if (actual < norm * TOO_FAST) flags.add("TOO_FAST");
                else if (actual > norm * TOO_SLOW) flags.add("SLOW");
            }
        }

        Integer late = null;
        if (ri.getDueTo() != null && ri.getDoneAt() != null && ri.getStatus() != RunItemStatus.SKIPPED) {
            OffsetDateTime due = s.getShiftDate().atTime(ri.getDueTo()).atZone(ZONE).toOffsetDateTime();
            long diff = Duration.between(due, ri.getDoneAt()).toMinutes();
            if (diff > 0) {
                late = (int) diff;
                flags.add("LATE");
            }
        }

        if (ri.getStatus() == RunItemStatus.SKIPPED) flags.add("SKIPPED");
        if (ri.getStatus() == RunItemStatus.PENDING && s.getFinishedAt() != null) flags.add("NOT_DONE");

        List<PhotoDto> photos = ri.getPhotos().stream()
                .map(ph -> new PhotoDto(ph.getId(), "/api/checklist/photos/" + ph.getId(), ph.getUploadedAt()))
                .toList();

        return new ItemReportDto(ri.getId(), ri.getSectionOrder(), ri.getSectionTitle(), ri.getTitle(),
                ri.getStatus(), ri.getDurationMin(), actual, ri.getDueFrom(), ri.getDueTo(), late,
                ri.getStartedAt(), ri.getDoneAt(), ri.getComment(), ri.getPhotoMode(), ri.isDirectorReview(),
                flags, photos);
    }

    private ShiftSummaryDto summary(ShiftSession s, List<ItemReportDto> items) {
        int completed = (int) items.stream().filter(i -> i.status() != RunItemStatus.PENDING).count();
        int problems = (int) items.stream().filter(i -> i.status() == RunItemStatus.PROBLEM).count();
        int photos = items.stream().mapToInt(i -> i.photos().size()).sum();
        int flagged = (int) items.stream().filter(i -> i.flags().stream().anyMatch(SERIOUS::contains)).count();
        AppUser u = s.getUser();
        return new ShiftSummaryDto(s.getId(), u.getId(), u.getFullName(), u.getLogin(),
                s.getOutlet().getId(), s.getOutlet().getName(), s.getShiftRole(), s.getDayPart(),
                s.getShiftDate(), s.getStartedAt(), s.getFinishedAt(),
                items.size(), completed, problems, photos, flagged);
    }

    /** null = видит всё (суперадмин); иначе только точки директора. */
    private Set<Long> scope(UserPrincipal viewer) {
        if (viewer.role() != AccountRole.DIRECTOR) return null;
        return users.findById(viewer.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
    }
}