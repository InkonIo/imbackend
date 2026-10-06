package com.imdemo.im.schedule.dto;

import com.imdemo.im.domain.DayPart;
import com.imdemo.im.domain.ShiftRole;
import com.imdemo.im.schedule.domain.AbsenceKind;
import com.imdemo.im.schedule.domain.JobTitle;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public final class ScheduleDto {
    private ScheduleDto() {}

    public record Staff(Long userId, String fullName, String login, JobTitle jobTitle,
                        boolean canInside, boolean schedulable, int maxShiftsWeek) {}

    public record Slot(LocalDate date, DayPart dayPart, ShiftRole role, Long userId, String userName,
                       boolean published) {}

    public record Absence(Long id, Long userId, String userName, AbsenceKind kind,
                          LocalDate from, LocalDate to, String note) {}

    public record Stat(Long userId, int shifts, int mornings, int evenings, int insides) {}

    public record Board(Long outletId, String outletName, LocalDate from, LocalDate to,
                        List<ShiftRole> morning, List<ShiftRole> evening,
                        List<Slot> slots, List<Staff> staff, List<Absence> absences,
                        List<Stat> stats, List<String> warnings) {}

    public record GenerateRequest(@NotNull Long outletId, @NotNull LocalDate from,
                                  @Min(1) @Max(62) int days,
                                  @NotEmpty List<ShiftRole> morning, @NotEmpty List<ShiftRole> evening,
                                  boolean keepFilled) {}

    public record SlotRef(@NotNull LocalDate date, @NotNull DayPart dayPart, @NotNull ShiftRole role) {}

    public record SlotRequest(@NotNull Long outletId, @NotNull LocalDate date, @NotNull DayPart dayPart,
                              @NotNull ShiftRole role, Long userId) {}

    public record SwapRequest(@NotNull Long outletId, @NotNull SlotRef a, @NotNull SlotRef b) {}

    public record PublishRequest(@NotNull Long outletId, @NotNull LocalDate from, @NotNull LocalDate to) {}

    public record StaffRequest(@NotNull JobTitle jobTitle, boolean canInside, boolean schedulable,
                               @Min(1) @Max(7) int maxShiftsWeek) {}

    public record AbsenceRequest(@NotNull Long userId, @NotNull AbsenceKind kind,
                                 @NotNull LocalDate from, @NotNull LocalDate to,
                                 @Size(max = 300) String note) {}

    public record Result(List<String> warnings) {}

    public record MySlot(LocalDate date, DayPart dayPart, ShiftRole role, Long outletId, String outletName) {}
}