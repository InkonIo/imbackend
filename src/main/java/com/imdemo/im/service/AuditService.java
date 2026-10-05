package com.imdemo.im.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.dto.Dto.AuditEventDto;
import com.imdemo.im.dto.Dto.AuditPage;
import com.imdemo.im.dto.Dto.UserBrief;
import com.imdemo.im.repo.AuditEventRepository;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.security.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

    private final AuditEventRepository repo;
    private final UserRepository users;

    // ---------- запись ----------

    /** Действие текущего пользователя (берётся из токена). Пишется в той же транзакции, что и само действие. */
    @Transactional
    public void log(AuditEventType type, ShiftSession shift, String entityType, Long entityId, String details) {
        AuditEvent e = base(type, entityType, entityId, details);
        fillActorFromToken(e);
        if (shift != null) {
            e.setShiftId(shift.getId());
            e.setOutletId(shift.getOutlet().getId());
        }
        repo.save(e);
    }

    @Transactional
    public void log(AuditEventType type, String entityType, Long entityId, String details) {
        log(type, null, entityType, entityId, details);
    }

    /** Для входа: токена ещё нет, пользователя передаём явно. */
    @Transactional
    public void logAs(AppUser actor, AuditEventType type, String details) {
        AuditEvent e = base(type, "user", actor.getId(), details);
        e.setUserId(actor.getId());
        e.setUserLogin(actor.getLogin());
        repo.save(e);
    }

    /** Неудачный вход: отдельная транзакция, чтобы запись не откатилась вместе с ошибкой 401. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailedLogin(String login, Long userId, String reason) {
        AuditEvent e = base(AuditEventType.LOGIN_FAILED, "user", userId, reason);
        e.setUserId(userId);
        e.setUserLogin(cut(login, 64));
        repo.save(e);
    }

    private AuditEvent base(AuditEventType type, String entityType, Long entityId, String details) {
        AuditEvent e = new AuditEvent();
        e.setType(type);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setDetails(cut(details, 1000));
        fillRequest(e);
        return e;
    }

    private void fillActorFromToken(AuditEvent e) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof UserPrincipal p) {
            e.setUserId(p.id());
            e.setUserLogin(p.login());
        }
    }

    private void fillRequest(AuditEvent e) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return;
        HttpServletRequest req = attrs.getRequest();
        // X-Forwarded-For можно подделать; доверяем ему только когда стоим за своим прокси (nginx и т.п.)
        String fwd = req.getHeader("X-Forwarded-For");
        e.setIp(cut(fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr(), 64));
        e.setUserAgent(cut(req.getHeader("User-Agent"), 300));
        e.setDeviceId(cut(req.getHeader("X-Device-Id"), 64));
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

        /** ID устройства из заголовка текущего запроса (или null). */
    public static String currentDeviceId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        return cut(attrs.getRequest().getHeader("X-Device-Id"), 64);
    }

    // ---------- чтение ----------

    @Transactional(readOnly = true)
    public AuditPage search(UserPrincipal viewer, Long userId, Long shiftId, Long outletId, AuditEventType type,
                            LocalDate from, LocalDate to, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);

        Set<Long> scope = null; // null = без ограничений (суперадмин)
        if (viewer.role() == AccountRole.DIRECTOR) {
            scope = users.findById(viewer.id())
                    .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                    .orElse(Set.of());
            if (scope.isEmpty()) return new AuditPage(List.of(), safePage, safeSize, 0);
        }
        final Set<Long> allowedOutlets = scope;

        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (userId != null) ps.add(cb.equal(root.get("userId"), userId));
            if (shiftId != null) ps.add(cb.equal(root.get("shiftId"), shiftId));
            if (outletId != null) ps.add(cb.equal(root.get("outletId"), outletId));
            if (type != null) ps.add(cb.equal(root.get("type"), type));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                    from.atStartOfDay(ZONE).toOffsetDateTime()));
            if (to != null) ps.add(cb.lessThan(root.get("createdAt"),
                    to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()));
            if (allowedOutlets != null) ps.add(root.get("outletId").in(allowedOutlets));
            return cb.and(ps.toArray(Predicate[]::new));
        };

        Page<AuditEvent> result = repo.findAll(spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id")));

        List<AuditEventDto> items = result.getContent().stream()
                .map(e -> new AuditEventDto(e.getId(), e.getCreatedAt(), e.getType(), e.getUserId(),
                        e.getUserLogin(), e.getShiftId(), e.getOutletId(), e.getEntityType(), e.getEntityId(),
                        e.getDetails(), e.getIp(), e.getUserAgent(), e.getDeviceId()))
                .toList();
        return new AuditPage(items, safePage, safeSize, result.getTotalElements());
    }

        /** Сотрудники для фильтра журнала: суперадмин видит всех, директор только людей своих точек. */
    @Transactional(readOnly = true)
    public List<UserBrief> visibleUsers(UserPrincipal viewer) {
        List<AppUser> all = users.findAll(Sort.by("fullName"));
        if (viewer.role() != AccountRole.DIRECTOR) {
            return all.stream().map(u -> new UserBrief(u.getId(), u.getFullName(), u.getLogin())).toList();
        }
        Set<Long> mine = users.findById(viewer.id())
                .map(u -> u.getOutlets().stream().map(Outlet::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
        return all.stream()
                .filter(u -> u.getOutlets().stream().anyMatch(o -> mine.contains(o.getId())))
                .map(u -> new UserBrief(u.getId(), u.getFullName(), u.getLogin()))
                .toList();
    }
}