package com.imdemo.im.schedule.service;

import com.imdemo.im.domain.AppUser;
import com.imdemo.im.ext.ManagerScheduleParser;
import com.imdemo.im.repo.UserRepository;
import com.imdemo.im.schedule.domain.ManagerScheduleEntry;
import com.imdemo.im.schedule.domain.ManagerWeek;
import com.imdemo.im.schedule.repository.ManagerScheduleRepository;
import com.imdemo.im.schedule.repository.ManagerWeekRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Загрузка «Расписание менеджеров» (Excel директора) в «График менеджеров».
 * Одна клетка = «ЧЧ:ММ-ЧЧ:ММ», перенос строки, задачи. Время идёт в смену, весь остальной текст
 * (позиция INS/K/C и задачи) целиком попадает в «Задачи на день». Повторная загрузка той же недели
 * обновляет клетки (время и задачи), а не создаёт дубли; клетки людей, которых нет в файле, не трогаем.
 */
@Service
@RequiredArgsConstructor
public class ExcelScheduleImporter {

    private final ManagerWeekRepository weekRepo;
    private final ManagerScheduleRepository entryRepo;
    private final UserRepository userRepo;

    public static class Result {
        public final List<String> log = new ArrayList<>();
        public String error;
    }

    private static final Pattern RANGE = Pattern.compile("^(\\d{1,2})[:.](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[:.](\\d{2})(.*)$", Pattern.DOTALL);

    public List<String> sheetNames(MultipartFile file) throws IOException {
        return ManagerScheduleParser.parse(file.getBytes()).stream().map(ManagerScheduleParser.Week::sheet).toList();
    }

    @Transactional
    public Result importSheet(Long outletId, MultipartFile file, String sheetName) throws IOException {
        Result res = new Result();
        List<ManagerScheduleParser.Week> weeks;
        try {
            weeks = ManagerScheduleParser.parse(file.getBytes());
        } catch (IllegalArgumentException e) {
            res.error = e.getMessage();
            return res;
        }
        if (sheetName != null && !sheetName.isBlank()) {
            weeks = weeks.stream().filter(w -> w.sheet().trim().equals(sheetName.trim())).toList();
        }
        if (weeks.isEmpty()) {
            res.error = "В файле не нашёл листов с расписанием: нужна шапка «Понедельник … Воскресенье» и даты под ней";
            return res;
        }

        List<AppUser> staff = userRepo.findAll().stream()
                .filter(AppUser::isActive)
                .filter(u -> u.getOutlets().stream().anyMatch(o -> o.getId().equals(outletId)))
                .toList();

        for (ManagerScheduleParser.Week w : weeks) {
            LocalDate monday = LocalDate.parse(w.weekStart());
            ManagerWeek week = weekRepo.findByOutletIdAndWeekStart(outletId, monday).orElse(null);
            boolean created = false;
            if (week == null) {
                week = new ManagerWeek();
                week.setOutletId(outletId);
                week.setWeekStart(monday);
                week.setTitle(w.sheet().trim());
                week = weekRepo.save(week);
                created = true;
            }
            Long weekId = week.getId();
            List<ManagerScheduleEntry> existing = entryRepo.findByWeekId(weekId);
            int added = 0, updated = 0, tasks = 0;
            List<String> skipped = new ArrayList<>();
            Set<String> unmatched = new TreeSet<>();

            for (ManagerScheduleParser.Person pr : w.people()) {
                AppUser u = match(pr.name(), staff);
                if (u == null) {
                    if (pr.cells().stream().anyMatch(c -> !"TASK".equals(c.kind()) || c.raw() != null)) unmatched.add(pr.name());
                    continue;
                }
                for (ManagerScheduleParser.Cell c : pr.cells()) {
                    if (c.date() == null) continue;
                    LocalDate day = LocalDate.parse(c.date());
                    boolean off = "OFF".equals(c.kind());
                    boolean shift = "SHIFT".equals(c.kind()) && c.warn() == null;
                    // клетка без времени, только текст («Инженерный обход 2 / планирование ПТО»): это задача дня
                    boolean taskOnly = "TASK".equals(c.kind()) && c.raw() != null && !c.raw().isBlank();
                    if (!off && !shift && !taskOnly) {
                        if (c.raw() != null && !c.raw().isBlank() && !"VACATION".equals(c.kind())) {
                            skipped.add(pr.name() + " · " + c.date().substring(8) + "." + c.date().substring(5, 7) + ": «" + shorten(c.raw()) + "», " + (c.warn() == null ? "не смена" : c.warn()));
                        }
                        continue;
                    }
                    ManagerScheduleEntry e = existing.stream()
                            .filter(x -> x.getUserId().equals(u.getId()) && x.getSchedDate().equals(day))
                            .findFirst().orElse(null);
                    boolean isNew = e == null;
                    if (isNew) e = new ManagerScheduleEntry();
                    e.setWeekId(weekId);
                    e.setOutletId(outletId);
                    e.setUserId(u.getId());
                    e.setSchedDate(day);
                    if (taskOnly) {
                        String text = c.raw().replaceAll("\\s+", " ").trim();
                        String prev = e.getComment() == null ? "" : e.getComment();
                        if (isNew) {
                            e.setShiftStart(null);
                            e.setShiftEnd(null);
                            e.setDayOff(false);
                            e.setComment(text);
                        } else if (!prev.contains(text)) {
                            e.setComment(prev.isBlank() || "**".equals(prev) ? text : prev + " · " + text);
                        }
                        tasks++;
                    } else if (off) {
                        e.setShiftStart(null);
                        e.setShiftEnd(null);
                        e.setDayOff(true);
                        e.setComment(c.raw() != null && c.raw().contains("**") ? "**" : null);
                    } else {
                        e.setShiftStart(LocalTime.parse(c.start()));
                        e.setShiftEnd(LocalTime.parse(c.end()));
                        e.setDayOff(false);
                        String text = tasksOf(c.raw());
                        e.setComment(text.isEmpty() ? null : text);
                        if (!text.isEmpty()) tasks++;
                    }
                    e.setUpdatedAt(OffsetDateTime.now());
                    ManagerScheduleEntry saved = entryRepo.save(e);
                    if (isNew) { existing.add(saved); added++; } else updated++;
                }
            }
            res.log.add((created ? "➕ Лист " : "♻ Лист ") + w.sheet().trim() + " (" + monday + "): новых клеток " + added
                    + ", обновлено " + updated + ", с задачами " + tasks);
            if (!unmatched.isEmpty()) res.log.add("   ⚠ Нет на сайте или имя не совпало: " + String.join(", ", unmatched));
            for (String s : skipped) res.log.add("   ⚠ Пропущено: " + s);
        }
        return res;
    }

    /** Всё после времени: «INS \ проверка часов х\к» или «Расписание работников / проверка часов ТТ». */
    static String tasksOf(String raw) {
        if (raw == null) return "";
        String s = raw.replace(' ', ' ').trim();
        Matcher m = RANGE.matcher(s);
        String rest = m.matches() ? m.group(5) : s;
        return rest.replaceAll("\\s+", " ").replaceFirst("^[\\s|/\\\\,.\\-–+]+", "").trim();
    }

    private static String shorten(String s) {
        String x = s.replaceAll("\\s+", " ").trim();
        return x.length() > 40 ? x.substring(0, 40) + "…" : x;
    }

    // ---- сопоставление имён: «Жексен Аружан» (Excel) ↔ «Аружан» (сайт)

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\s-]", " ").trim();
    }

    private static Set<String> tokens(String s) {
        Set<String> t = new TreeSet<>();
        for (String x : norm(s).split("\\s+")) if (!x.isEmpty()) t.add(x);
        return t;
    }

    private static AppUser match(String excelName, List<AppUser> staff) {
        Set<String> t = tokens(excelName);
        if (t.isEmpty()) return null;
        for (AppUser u : staff) if (tokens(u.getFullName()).equals(t)) return u;
        AppUser found = null;
        for (AppUser u : staff) {                       // имя на сайте целиком есть в имени из Excel
            Set<String> ut = tokens(u.getFullName());
            if (!ut.isEmpty() && t.containsAll(ut)) {
                if (found != null) { found = null; break; }
                found = u;
            }
        }
        if (found != null) return found;
        for (AppUser u : staff) {                       // имя из Excel целиком есть в имени на сайте
            if (tokens(u.getFullName()).containsAll(t)) {
                if (found != null) return null;
                found = u;
            }
        }
        return found;
    }
}