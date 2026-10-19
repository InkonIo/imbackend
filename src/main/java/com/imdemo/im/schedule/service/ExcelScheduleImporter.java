package com.imdemo.im.schedule.service;

import com.imdemo.im.domain.AppUser;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.schedule.domain.*;
import com.imdemo.im.schedule.repository.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Service
@RequiredArgsConstructor
public class ExcelScheduleImporter {

    private final ManagerWeekRepository weekRepo;
    private final ManagerScheduleRepository entryRepo;
    private final UserRepository userRepo;

    private static final Pattern TIME_PAT = Pattern.compile(
        "(\\d{1,2})[:h](\\d{2})\\s*[-\u2013]\\s*(\\d{1,2})[:h](\\d{2})");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    public static class ImportResult {
        public final List<String> log;
        public final String error;
        public ImportResult(List<String> log, String error) { this.log = log; this.error = error; }
    }

    public List<String> sheetNames(MultipartFile file) throws IOException {
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) names.add(wb.getSheetName(i));
            return names;
        }
    }

    @Transactional
    public ImportResult importSheet(Long outletId, MultipartFile file, String sheetName) throws IOException {
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = sheetName != null ? wb.getSheet(sheetName) : wb.getSheetAt(0);
            if (sheet == null) sheet = wb.getSheetAt(0);
            return parseSheet(outletId, sheet);
        }
    }

    private ImportResult parseSheet(Long outletId, Sheet sheet) {
        Row dateRow = sheet.getRow(3);
        if (dateRow == null) return new ImportResult(List.of(), "Нет строки с датами (строка 4)");

        LocalDate[] dates = new LocalDate[7];
        for (int col = 1; col <= 7; col++) {
            Cell c = dateRow.getCell(col);
            if (c != null && c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                dates[col - 1] = c.getLocalDateTimeCellValue().toLocalDate();
            }
        }
        LocalDate weekStart = null;
        for (LocalDate d : dates) { if (d != null) { weekStart = d; break; } }
        if (weekStart == null) return new ImportResult(List.of(), "Не найдены даты в строке 4");

        final LocalDate ws = weekStart;
        ManagerWeek week = weekRepo.findByOutletIdAndWeekStart(outletId, ws).orElseGet(() -> {
            ManagerWeek w = new ManagerWeek();
            w.setOutletId(outletId); w.setWeekStart(ws);
            w.setTitle(sheet.getSheetName());
            return weekRepo.save(w);
        });

        List<AppUser> users = userRepo.findAll().stream()
            .filter(AppUser::isActive)
            .filter(u -> u.getOutlets().stream().anyMatch(o -> o.getId().equals(outletId)))
            .toList();

        List<String> log = new ArrayList<>();
        int saved = 0;

        for (int rowIdx = 4; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;
            Cell nameCell = row.getCell(0);
            if (nameCell == null) continue;
            String rawName = nameCell.getStringCellValue().trim();
            if (rawName.isBlank()) continue;
            if (rawName.contains("Подпись") || rawName.startsWith("Дата:")
                || rawName.contains("Консультант")) continue;

            AppUser user = findUser(users, rawName);
            if (user == null) { log.add("\u26a0 Не найден: " + rawName); continue; }

            for (int col = 1; col <= 7; col++) {
                LocalDate date = dates[col - 1];
                if (date == null) continue;
                String raw = cellText(row.getCell(col));
                if (raw.isBlank()) continue;

                ManagerScheduleEntry entry = entryRepo
                    .findByWeekIdAndUserIdAndSchedDate(week.getId(), user.getId(), date)
                    .orElseGet(ManagerScheduleEntry::new);
                entry.setWeekId(week.getId());
                entry.setOutletId(outletId);
                entry.setUserId(user.getId());
                entry.setSchedDate(date);

                String upper = raw.toUpperCase();
                if (upper.startsWith("OFF**")) {
                    entry.setDayOff(true); entry.setShiftStart(null); entry.setShiftEnd(null);
                    entry.setComment("**");
                } else if (upper.startsWith("OFF")) {
                    entry.setDayOff(true); entry.setShiftStart(null); entry.setShiftEnd(null);
                    entry.setComment(null);
                } else {
                    entry.setDayOff(false);
                    Matcher m = TIME_PAT.matcher(raw);
                    if (m.find()) {
                        entry.setShiftStart(LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                        entry.setShiftEnd(LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
                    }
                    String[] lines = raw.split("\n", 2);
                    entry.setComment(lines.length > 1 ? lines[1].strip() : null);
                }
                entryRepo.save(entry);
                saved++;
            }
        }
        log.add(0, "\u2713 Импортировано записей: " + saved);
        return new ImportResult(log, null);
    }

    private String cellText(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                ? c.getLocalDateTimeCellValue().toLocalTime().format(FMT)
                : String.valueOf((long) c.getNumericCellValue());
            default -> "";
        };
    }

    private AppUser findUser(List<AppUser> users, String excelName) {
        String lower = excelName.toLowerCase().trim();
        for (AppUser u : users)
            if (u.getFullName() != null && u.getFullName().toLowerCase().trim().equals(lower)) return u;
        String[] parts = lower.split("\\s+");
        for (AppUser u : users) {
            if (u.getLogin() == null) continue;
            String login = u.getLogin().toLowerCase();
            for (String p : parts)
                if (p.length() > 2 && login.equals(p)) return u;
        }
        for (AppUser u : users) {
            if (u.getFullName() == null || u.getFullName().isBlank()) continue;
            String fn = u.getFullName().toLowerCase().trim();
            for (String p : parts)
                if (p.length() > 2 && (fn.equals(p) || lower.contains(fn) || fn.contains(p))) return u;
        }
        return null;
    }
}
