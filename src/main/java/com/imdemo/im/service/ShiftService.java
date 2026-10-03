package com.imdemo.im.service;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.AppUser;
import com.imdemo.im.domain.AuditEventType;
import com.imdemo.im.domain.Outlet;
import com.imdemo.im.domain.ShiftSession;
import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.dto.Mappers;
import com.imdemo.im.repo.OutletRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.web.error.ApiException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

    private final ShiftSessionRepository shifts;
    private final UserRepository users;
    private final OutletRepository outlets;
    private final ChecklistService checklistService;
    private final AuditService audit;
    private final EntityManager em;

    @Transactional
    public ShiftDto start(Long userId, StartShiftRequest r) {
        AppUser u = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
        if (u.isMustChangePassword()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Сначала смени пароль");
        }
        if (shifts.findFirstByUserIdAndFinishedAtIsNull(userId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Смена уже начата, сначала заверши её");
        }
        Outlet o = outlets.findById(r.outletId()).filter(Outlet::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Точка не найдена"));
        boolean allowed = u.getAccountRole() == AccountRole.SUPER_ADMIN
                || u.getOutlets().stream().anyMatch(x -> x.getId().equals(o.getId()));
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к этой точке");
        }

        ShiftSession s = new ShiftSession();
        s.setUser(u);
        s.setOutlet(o);
        s.setShiftRole(r.shiftRole());
        s.setDayPart(r.dayPart());
        s.setShiftDate(LocalDate.now(ZONE));
        shifts.saveAndFlush(s);
        em.refresh(s);

        checklistService.createRun(s);
        audit.log(AuditEventType.SHIFT_STARTED, s, "shift", s.getId(),
                r.shiftRole() + " · " + r.dayPart() + " · " + o.getName());
        return Mappers.shift(s);
    }

    @Transactional(readOnly = true)
    public Optional<ShiftDto> current(Long userId) {
        return shifts.findFirstByUserIdAndFinishedAtIsNull(userId).map(Mappers::shift);
    }

    @Transactional
    public ShiftDto finish(Long userId) {
        ShiftSession s = shifts.findFirstByUserIdAndFinishedAtIsNull(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Активной смены нет"));
        s.setFinishedAt(OffsetDateTime.now());
        audit.log(AuditEventType.SHIFT_FINISHED, s, "shift", s.getId(),
                checklistService.progressText(s.getId()).orElse("без чек-листа"));
        return Mappers.shift(s);
    }
}