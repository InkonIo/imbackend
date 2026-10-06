package com.imdemo.im.dto;

import com.imdemo.im.domain.AccountRole;
import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.ShiftRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.imdemo.im.domain.AuditEventType;

import com.imdemo.im.domain.PhotoMode;
import com.imdemo.im.domain.RunItemStatus;
import java.time.LocalTime;

import com.imdemo.im.domain.FlagSeverity;
import com.imdemo.im.domain.FlagType;
import com.imdemo.im.domain.ReviewStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public final class Dto {
    private Dto() {}

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {}

    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank @Size(min = 8, max = 64) String newPassword) {}

    public record CityDto(Long id, String name) {}

    public record CityRequest(@NotBlank @Size(max = 100) String name) {}

    public record OutletDto(Long id, Long cityId, String cityName, String name, String address, boolean active) {}

    public record OutletRequest(
            @NotNull Long cityId,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 255) String address,
            Boolean active) {}

    public record UserDto(Long id, String login, String fullName, AccountRole accountRole,
                          boolean active, boolean mustChangePassword, List<OutletDto> outlets) {}

    public record LoginResponse(String token, UserDto user) {}

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 64) String login,
            @NotBlank @Size(max = 150) String fullName,
            @NotNull AccountRole accountRole,
            Set<Long> outletIds) {}

    public record UpdateUserRequest(
            @Size(max = 150) String fullName,
            AccountRole accountRole,
            Boolean active,
            Set<Long> outletIds) {}

    public record UserWithPassword(UserDto user, String temporaryPassword) {}

    public record StartShiftRequest(
            @NotNull Long outletId,
            @NotNull ShiftRole shiftRole,
            @NotNull DayPart dayPart) {}

        public record PhotoDto(Long id, String url, OffsetDateTime uploadedAt) {}

        public record RunItemDto(Long id, int sectionOrder, String sectionTitle, int sortOrder, String title,
                             String instructions,
                             Integer durationMin, LocalTime dueFrom, LocalTime dueTo,
                             PhotoMode photoMode, boolean directorReview, boolean timed,
                             RunItemStatus status, String comment,
                             OffsetDateTime startedAt, OffsetDateTime doneAt, OffsetDateTime reopenUntil,
                             List<PhotoDto> photos, String action) {}

    public record AuditEventDto(Long id, OffsetDateTime createdAt, AuditEventType type,
                                Long userId, String userLogin, Long shiftId, Long outletId,
                                String entityType, Long entityId, String details,
                                String ip, String userAgent, String deviceId) {}

    public record AuditPage(List<AuditEventDto> items, int page, int size, long total) {}

    public record ChecklistDto(Long runId, Long shiftId, String title,
                               int total, int completed, int problems,
                               List<RunItemDto> items) {}

    public record UpdateRunItemRequest(
            @NotNull RunItemStatus status,
            @Size(max = 1000) String comment) {}

        public record FlagDto(Long id, FlagType type, FlagSeverity severity, String details,
                          OffsetDateTime createdAt, Long runItemId, Long photoId, ReviewStatus reviewStatus) {}

    public record ShiftSummaryDto(Long shiftId, Long userId, String userName, String userLogin,
                                  Long outletId, String outletName, ShiftRole shiftRole, DayPart dayPart,
                                  LocalDate shiftDate, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                  int total, int completed, int problems, int photos, int flagged, int activeMin) {}

    public record ItemReportDto(Long id, int sectionOrder, String sectionTitle, String title,
                                RunItemStatus status, Integer normMin, Integer actualMin,
                                LocalTime dueFrom, LocalTime dueTo, Integer lateMin,
                                OffsetDateTime startedAt, OffsetDateTime doneAt, String comment,
                                PhotoMode photoMode, boolean directorReview,
                                List<FlagDto> flags, List<PhotoDto> photos) {}

    public record ShiftReportDto(ShiftSummaryDto shift, List<FlagDto> shiftFlags, List<ItemReportDto> items) {}
    public record UserBrief(Long id, String fullName, String login) {}

    public record ShiftDto(Long id, Long outletId, String outletName, ShiftRole shiftRole, DayPart dayPart,
                           LocalDate shiftDate, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}
}