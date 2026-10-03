package com.imdemo.im.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.repo.AuditFlagRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

    private final ShiftSessionRepository shifts;
    private final ChecklistRunRepository runs;
    private final UserRepository users;
    private final AuditFlagRepository flagRepo;
    private final ActivityStore activity;

    @Transactional(readOnly = true)
    public List<ShiftSummaryDto> list(UserPrincipal viewer, LocalDate date) {
        Set<Long> scope = scope(viewer);
        List<ShiftSession> list;
        if (scope == null) list = shifts.findByShiftDateOrderByStartedAtAsc(date);
        else if (scope.isEmpty()) list = List.of();
        else list = shifts.findByShiftDateAndOutletIdInOrderByStartedAtAsc(date, scope);
        if (list.isEmpty()) return List.of();

        List<Long> ids = list.stream().map(ShiftSession::getId).toList();
        Map<Long, List<FlagDto>> flagsByShift = flagRepo.findByShiftIdIn(ids).stream()
                .collect(Collectors.groupingBy(AuditFlag::getShiftId,
                        Collectors.mapping(ShiftReportService::flag, Collectors.toList())));
        Map<Long, Integer> active = activity.activeMinutes(ids);

        return list.stream().map(s -> {
            List<FlagDto> fl = flagsByShift.getOrDefault(s.getId(), List.of());
            return summary(s, items(s, fl), fl, active.getOrDefault(s.getId(), 0));
        }).toList();
    }

    @Transactional(readOnly = true)
    public ShiftReportDto report(UserPrincipal viewer, Long shiftId) {
        ShiftSession s = shifts.findById(shiftId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Смена не найдена"));
        Set<Long> scope = scope(viewer);
        if (scope != null && !scope.contains(s.getOutlet().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой точке");
        }
        List<FlagDto> fl = flagRepo.findByShiftIdOrderByCreatedAtAsc(shiftId).stream()
                .map(ShiftReportService::flag).toList();
        List<ItemReportDto> items = items(s, fl);
        List<FlagDto> shiftFlags = fl.stream().filter(f -> f.runItemId() == null).toList();
        int activeMin = activity.activeMinutes(List.of(shiftId)).getOrDefault(shiftId, 0);
        return new ShiftReportDto(summary(s, items, fl, activeMin), shiftFlags, items);
    }

    // ---------- расчёт ----------

    private List<ItemReportDto> items(ShiftSession s, List<FlagDto> allFlags) {
        Map<Long, List<FlagDto>> byItem = allFlags.stream()
                .filter(f -> f.runItemId() != null)
                .collect(Collectors.groupingBy(FlagDto::runItemId));
        return runs.findByShiftId(s.getId())
                .map(run -> run.getItems().stream()
                        .map(ri -> item(s, ri, byItem.getOrDefault(ri.getId(), List.of())))
                        .toList())
                .orElse(List.of());
    }

    private ItemReportDto item(ShiftSession s, ChecklistRunItem ri, List<FlagDto> flags) {
        Integer actual = null;
        if (ri.getStartedAt() != null && ri.getDoneAt() != null) {
            actual = (int) Duration.between(ri.getStartedAt(), ri.getDoneAt()).toMinutes();
        }
        Integer late = null;
        if (ri.getDueTo() != null && ri.getDoneAt() != null && ri.getStatus() != RunItemStatus.SKIPPED) {
            OffsetDateTime due = s.getShiftDate().atTime(ri.getDueTo()).atZone(ZONE).toOffsetDateTime();
            long diff = Duration.between(due, ri.getDoneAt()).toMinutes();
            if (diff > 0) late = (int) diff;
        }
        List<PhotoDto> photos = ri.getPhotos().stream()
                .map(ph -> new PhotoDto(ph.getId(), "/api/checklist/photos/" + ph.getId(), ph.getUploadedAt()))
                .toList();
        return new ItemReportDto(ri.getId(), ri.getSectionOrder(), ri.getSectionTitle(), ri.getTitle(),
                ri.getStatus(), ri.getDurationMin(), actual, ri.getDueFrom(), ri.getDueTo(), late,
                ri.getStartedAt(), ri.getDoneAt(), ri.getComment(), ri.getPhotoMode(), ri.isDirectorReview(),
                flags, photos);
    }

    private ShiftSummaryDto summary(ShiftSession s, List<ItemReportDto> items, List<FlagDto> flags, int activeMin) {
        int completed = (int) items.stream().filter(i -> i.status() != RunItemStatus.PENDING).count();
        int problems = (int) items.stream().filter(i -> i.status() == RunItemStatus.PROBLEM).count();
        int photos = items.stream().mapToInt(i -> i.photos().size()).sum();
        int flagged = (int) flags.stream().filter(f -> f.severity() != FlagSeverity.LOW).count();
        AppUser u = s.getUser();
        return new ShiftSummaryDto(s.getId(), u.getId(), u.getFullName(), u.getLogin(),
                s.getOutlet().getId(), s.getOutlet().getName(), s.getShiftRole(), s.getDayPart(),
                s.getShiftDate(), s.getStartedAt(), s.getFinishedAt(),
                items.size(), completed, problems, photos, flagged, activeMin);
    }

    private static FlagDto flag(AuditFlag f) {
        return new FlagDto(f.getId(), f.getType(), f.getSeverity(), f.getDetails(), f.getCreatedAt(),
                f.getRunItemId(), f.getPhotoId(), f.getReviewStatus());
    }

    private Set<Long> scope(UserPrincipal viewer) {
        if (viewer.role() != AccountRole.DIRECTOR) return null;
        return users.findById(viewer.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
    }
}