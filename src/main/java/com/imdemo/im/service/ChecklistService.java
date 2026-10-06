package com.imdemo.im.service;

import com.imdemo.im.domain.*;
import com.imdemo.im.dto.Dto.*;
import com.imdemo.im.repo.ChecklistRunItemRepository;
import com.imdemo.im.repo.ChecklistRunRepository;
import com.imdemo.im.repo.ChecklistTemplateRepository;
import com.imdemo.im.repo.ShiftSessionRepository;
import com.imdemo.im.security.UserPrincipal;
import com.imdemo.im.web.error.ApiException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChecklistService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");
    /** Пункты с нормой от 10 минут требуют «Начать» → «Готово». */
    public static final int TIMED_MIN = 10;
    private static final Duration REOPEN_WINDOW = Duration.ofMinutes(5);
    private static final Duration EARLY_GRACE = Duration.ofMinutes(5);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final ChecklistTemplateRepository templates;
    private final ChecklistRunRepository runs;
    private final ChecklistRunItemRepository runItems;
    private final ShiftSessionRepository shifts;
    private final PhotoStorage storage;
    private final AuditService audit;
    private final FlagService flags;
    private final EntityManager em;

    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final com.imdemo.im.inventory.repository.InventoryCountRepository inventoryCounts;

    public record PhotoFile(Path path, String contentType) {}

    // ---------- создание прогона ----------

    @Transactional
    public Optional<ChecklistRun> createRun(ShiftSession shift) {
                Long outletId = shift.getOutlet().getId();
        return templates.findFirstByShiftRoleAndDayPartAndOutletIdAndActiveTrue(
                        shift.getShiftRole(), shift.getDayPart(), outletId)
                .or(() -> templates.findFirstByShiftRoleAndDayPartAndOutletIdIsNullAndActiveTrue(
                        shift.getShiftRole(), shift.getDayPart()))
                .map(template -> {
                    ChecklistRun run = new ChecklistRun();
                    run.setShift(shift);
                    run.setTemplate(template);
                    int weekday = shift.getShiftDate().getDayOfWeek().getValue();
                    for (ChecklistSection section : template.getSections()) {
                        for (ChecklistItem item : section.getItems()) {
                            if (!item.isActive()) continue;
                            if (item.getWeekday() != null && item.getWeekday() != weekday) continue;
                            run.getItems().add(copy(run, section, item));
                        }
                    }
                    return runs.saveAndFlush(run);
                });
    }

    private ChecklistRunItem copy(ChecklistRun run, ChecklistSection section, ChecklistItem item) {
        ChecklistRunItem ri = new ChecklistRunItem();
        ri.setAction(item.getAction());
        ri.setInstructions(item.getInstructions());
        ri.setTelegramNotify(item.isTelegramNotify());
        ri.setRun(run);
        ri.setItem(item);
        ri.setSectionOrder(section.getSortOrder());
        ri.setSectionTitle(section.getTitle());
        ri.setSortOrder(item.getSortOrder());
        ri.setTitle(item.getTitle());
        ri.setDurationMin(item.getDurationMin());
        ri.setDueFrom(item.getDueFrom());
        ri.setDueTo(item.getDueTo());
        ri.setPhotoMode(item.getPhotoMode());
        ri.setDirectorReview(item.isDirectorReview());
        return ri;
    }

    // ---------- чтение ----------

    @Transactional
    public Optional<ChecklistDto> current(Long userId) {
        Optional<ShiftSession> shift = shifts.findFirstByUserIdAndFinishedAtIsNull(userId);
        if (shift.isEmpty()) return Optional.empty();
        ShiftSession s = shift.get();
        Optional<ChecklistRun> run = runs.findByShiftId(s.getId());
        if (run.isEmpty()) run = createRun(s);
        return run.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<String> progressText(Long shiftId) {
        return runs.findByShiftId(shiftId).map(run -> {
            long closed = run.getItems().stream().filter(i -> i.getStatus() != RunItemStatus.PENDING).count();
            long problems = run.getItems().stream().filter(i -> i.getStatus() == RunItemStatus.PROBLEM).count();
            return "закрыто " + closed + "/" + run.getItems().size() + ", проблем: " + problems;
        });
    }

    // ---------- старт пункта с таймером ----------

    @Transactional
    public ChecklistDto start(Long userId, Long runItemId) {
        ChecklistRunItem ri = ownedOpenItem(userId, runItemId);
        if (!isTimed(ri)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Этот пункт закрывается сразу, без таймера");
        }
        requirePending(ri);
        if (ri.getStartedAt() != null) return toDto(ri.getRun());
        ensureWindowOpen(ri);
        ri.setStartedAt(OffsetDateTime.now());
        audit.log(AuditEventType.ITEM_STARTED, shiftOf(ri), "run_item", ri.getId(), ri.getTitle());
        flags.checkDevice(shiftOf(ri));
        return toDto(ri.getRun());
    }

    // ---------- отметка пункта ----------

    @Transactional
    public ChecklistDto update(Long userId, Long runItemId, UpdateRunItemRequest r) {
        ChecklistRunItem ri = ownedOpenItem(userId, runItemId);
        String comment = r.comment() == null ? null : r.comment().trim();
        if (comment != null && comment.isEmpty()) comment = null;
        boolean hasPhoto = !ri.getPhotos().isEmpty();
        OffsetDateTime now = OffsetDateTime.now();

        AuditEventType event;
        String details = ri.getTitle();

        switch (r.status()) {
            case PENDING -> {
                if (ri.getStatus() == RunItemStatus.PENDING) return toDto(ri.getRun());
                if (ri.getDoneAt() == null || ri.getDoneAt().plus(REOPEN_WINDOW).isBefore(now)) {
                    throw new ApiException(HttpStatus.FORBIDDEN,
                            "Вернуть можно только в течение 5 минут после закрытия. Дальше только через директора");
                }
                event = AuditEventType.ITEM_REOPENED;
                details += " · было: " + ri.getStatus();
            }
            case DONE -> {
                requirePending(ri);
                ensureWindowOpen(ri);
                if (isTimed(ri) && ri.getStartedAt() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Сначала нажми «Начать»: у пункта есть норма времени");
                }
                if (ri.getPhotoMode() == PhotoMode.REQUIRED && !hasPhoto) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Для этого пункта нужно фото");
                }
                if ("INVENTORY".equals(ri.getAction()) && !inventoryCounts.existsByOutletIdAndCountDateAndStatus(
                        shiftOf(ri).getOutlet().getId(), shiftOf(ri).getShiftDate(),
                        com.imdemo.im.inventory.domain.CountStatus.SUBMITTED)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "Сначала сдай инвентаризацию: раздел «📦 Инвентаризация»");
                }
                event = AuditEventType.ITEM_DONE;
                details += timingDetails(ri, now);

                
            }
            case PROBLEM -> {
                requirePending(ri);
                if (comment == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Опиши проблему в комментарии");
                }
                if (ri.getPhotoMode() != PhotoMode.NONE && !hasPhoto) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Приложи фото проблемы");
                }
                event = AuditEventType.ITEM_PROBLEM;
                details += " · " + comment;
            }
            case SKIPPED -> {
                requirePending(ri);
                ensureWindowOpen(ri);
                event = AuditEventType.ITEM_SKIPPED;
            }
            default -> throw new IllegalStateException("Неизвестный статус");
        }

        ri.setStatus(r.status());
        ri.setComment(r.status() == RunItemStatus.PENDING ? null : comment);
        ri.setDoneAt(r.status() == RunItemStatus.PENDING ? null : now);
        audit.log(event, shiftOf(ri), "run_item", ri.getId(), details);

        if (r.status() != RunItemStatus.PENDING) {
            flags.onItemClosed(ri, now);
        }

                // в Telegram: если пункт проверяет директор или включена отправка
        if ((ri.isDirectorReview() || ri.isTelegramNotify())
                && (r.status() == RunItemStatus.DONE || r.status() == RunItemStatus.PROBLEM)) {
            publisher.publishEvent(new com.imdemo.im.events.AppEvents.ReviewNeeded(ri.getId()));
        }
        flags.checkDevice(shiftOf(ri));
        return toDto(ri.getRun());
    }

    // ---------- фото ----------

    @Transactional
    public ChecklistDto addPhoto(Long userId, Long runItemId, MultipartFile file, Long takenAtMs) {
        ChecklistRunItem ri = ownedOpenItem(userId, runItemId);
        requirePending(ri);
        if (ri.getPhotos().size() >= 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Максимум 5 фото на пункт");
        }
        PhotoStorage.Saved saved = storage.save(file);
        ChecklistPhoto photo = new ChecklistPhoto();
        photo.setRunItem(ri);
        photo.setFilePath(saved.path());
        photo.setSha256(saved.sha256());
        photo.setTakenAt(toTakenAt(takenAtMs));
        ri.getPhotos().add(photo);
        em.flush();

        audit.log(AuditEventType.PHOTO_UPLOADED, shiftOf(ri), "photo", photo.getId(),
                ri.getTitle() + " · фото " + ri.getPhotos().size());
        flags.onPhotoUploaded(ri, photo, OffsetDateTime.now());
        flags.checkDevice(shiftOf(ri));
        return toDto(ri.getRun());
    }

    @Transactional
    public ChecklistDto deletePhoto(Long userId, Long photoId) {
        ChecklistPhoto photo = em.find(ChecklistPhoto.class, photoId);
        if (photo == null) throw new ApiException(HttpStatus.NOT_FOUND, "Фото не найдено");
        ChecklistRunItem ri = ownedOpenItem(userId, photo.getRunItem().getId());
        if (ri.getStatus() != RunItemStatus.PENDING) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Нельзя удалить фото у закрытого пункта");
        }
        String path = photo.getFilePath();
        ri.getPhotos().remove(photo);
        em.flush();
        storage.delete(path);
        audit.log(AuditEventType.PHOTO_DELETED, shiftOf(ri), "photo", photoId, ri.getTitle());
        return toDto(ri.getRun());
    }

    @Transactional(readOnly = true)
    public PhotoFile photo(UserPrincipal p, Long photoId) {
        ChecklistPhoto photo = em.find(ChecklistPhoto.class, photoId);
        if (photo == null) throw new ApiException(HttpStatus.NOT_FOUND, "Фото не найдено");
        Long ownerId = photo.getRunItem().getRun().getShift().getUser().getId();
        boolean allowed = ownerId.equals(p.id())
                || p.role() == AccountRole.SUPER_ADMIN
                || p.role() == AccountRole.DIRECTOR;
        if (!allowed) throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа к фото");
        return new PhotoFile(storage.resolve(photo.getFilePath()), storage.contentType(photo.getFilePath()));
    }

    // ---------- правила ----------

    private static boolean isTimed(ChecklistRunItem ri) {
        return ri.getDurationMin() != null && ri.getDurationMin() >= TIMED_MIN;
    }

    private static void requirePending(ChecklistRunItem ri) {
        if (ri.getStatus() != RunItemStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "Пункт уже закрыт. Сначала верни его");
        }
    }

    private void ensureWindowOpen(ChecklistRunItem ri) {
        if (ri.getDueFrom() == null) return;
        OffsetDateTime opensAt = ShiftClock.at(shiftOf(ri).getShiftDate(), ri.getDueFrom()).minus(EARLY_GRACE);
        if (OffsetDateTime.now().isBefore(opensAt)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Ещё рано: пункт доступен с " + ri.getDueFrom().format(HM));
        }
    }

    /** Время съёмки от клиента (EXIF или дата файла). Явный мусор отбрасываем. */
    private static OffsetDateTime toTakenAt(Long ms) {
        if (ms == null || ms <= 0) return null;
        if (ms > System.currentTimeMillis() + Duration.ofMinutes(5).toMillis()) return null;
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZONE);
    }

    private static String timingDetails(ChecklistRunItem ri, OffsetDateTime now) {
        StringBuilder sb = new StringBuilder();
        if (ri.getStartedAt() != null) {
            long min = Duration.between(ri.getStartedAt(), now).toMinutes();
            sb.append(" · ").append(min).append(" мин");
            if (ri.getDurationMin() != null) sb.append(" (норма ").append(ri.getDurationMin()).append(")");
        }
        if (ri.getDueTo() != null) sb.append(" · срок до ").append(ri.getDueTo().format(HM));
        return sb.toString();
    }

    private static ShiftSession shiftOf(ChecklistRunItem ri) {
        return ri.getRun().getShift();
    }

    private ChecklistRunItem ownedOpenItem(Long userId, Long runItemId) {
        ChecklistRunItem ri = runItems.findById(runItemId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пункт не найден"));
        ShiftSession s = shiftOf(ri);
        if (!s.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Это не твоя смена");
        }
        if (s.getFinishedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Смена уже завершена");
        }
        return ri;
    }

    // ---------- маппинг ----------

    private ChecklistDto toDto(ChecklistRun run) {
        List<RunItemDto> items = run.getItems().stream().map(this::toDto).toList();
        int completed = (int) items.stream().filter(i -> i.status() != RunItemStatus.PENDING).count();
        int problems = (int) items.stream().filter(i -> i.status() == RunItemStatus.PROBLEM).count();
        return new ChecklistDto(run.getId(), run.getShift().getId(), run.getTemplate().getTitle(),
                items.size(), completed, problems, items);
    }

    private RunItemDto toDto(ChecklistRunItem ri) {
        List<PhotoDto> photos = ri.getPhotos().stream()
                .map(ph -> new PhotoDto(ph.getId(), "/api/checklist/photos/" + ph.getId(), ph.getUploadedAt()))
                .toList();
        OffsetDateTime reopenUntil = ri.getDoneAt() == null ? null : ri.getDoneAt().plus(REOPEN_WINDOW);
        return new RunItemDto(ri.getId(), ri.getSectionOrder(), ri.getSectionTitle(), ri.getSortOrder(),
                ri.getTitle(), ri.getInstructions(), ri.getDurationMin(), ri.getDueFrom(), ri.getDueTo(),
                ri.getPhotoMode(), ri.isDirectorReview(), isTimed(ri),
                ri.getStatus(), ri.getComment(), ri.getStartedAt(), ri.getDoneAt(), reopenUntil, photos, ri.getAction());
    }
}