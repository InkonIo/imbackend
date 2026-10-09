package com.imdemo.im.ext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Чистая логика сборки недели (без базы и Spring): получает данные, возвращает клетки.
 * Время везде в минутах от полуночи дня начала смены. Конец смены может быть больше 1440 (переход через полночь).
 *
 * Правила жёсткие: человек умеет позицию; одна смена в день; нет отпуска/больничного/одобренного выходного;
 * не пересекается с «не могу в эти часы»; если есть «могу только в эти часы», смена целиком внутри окна;
 * отдых между сменами не меньше minRestMin; не больше maxConsecutive рабочих дней подряд.
 * Недельной нормы нет. Среди подходящих выбирается тот, у кого в неделе меньше смен
 * (при равенстве — у кого меньше позиций, чтобы универсалов оставлять на дефицитные слоты).
 * Если подходящих нет, слот остаётся пустым (empId = null) и показывается красным.
 */
public final class PlanGenerator {

    private PlanGenerator() {}

    public record Emp(long id, String name, Set<String> positions) {}

    /** Норма: сколько человек позиции pos нужно в часть дня part в часы start–end. */
    public record SlotDef(String pos, String part, int need, int start, int end, int sort) {}

    /** Заранее поставленная смена (обучение TR/TRN или ручная правка). */
    public record Fixed(LocalDate day, String pos, String part, int start, int end, long empId, String note) {}

    /** Недоступность (available=false) или «только в эти часы» (available=true) в конкретный день. */
    public record Window(long empId, LocalDate day, int from, int to, boolean available) {}

    /** Уже стоящая в Таймтрекере смена до начала недели (для отдыха и серии рабочих дней). */
    public record Hist(long empId, LocalDate day, int start, int end) {}

    public record Input(LocalDate from, int days, List<Emp> emps, List<SlotDef> slots, List<Fixed> fixed,
                        Set<String> blocked, List<Window> windows, List<Hist> history,
                        int maxConsecutive, int minRestMin) {}

    public record Cell(LocalDate day, String pos, String part, int start, int end, Long empId, String note) {}

    public record Result(List<Cell> cells, List<String> warnings) {}

    public static String blockKey(long empId, LocalDate day) {
        return empId + "|" + day;
    }

    // ------------------------------------------------------------------------------------------------

    public static Result generate(Input in) {
        Map<Long, Emp> emps = new HashMap<>();
        for (Emp e : in.emps()) emps.put(e.id(), e);

        Map<String, List<Window>> win = new HashMap<>();
        for (Window w : in.windows()) win.computeIfAbsent(blockKey(w.empId(), w.day()), k -> new ArrayList<>()).add(w);

        Map<Long, TreeMap<LocalDate, int[]>> shifts = new HashMap<>();
        for (Hist h : in.history()) shifts.computeIfAbsent(h.empId(), k -> new TreeMap<>()).put(h.day(), new int[] {h.start(), h.end()});

        List<Cell> cells = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Long, Integer> weekCount = new HashMap<>();
        LocalDate last = in.from().plusDays(in.days() - 1L);

        // 1) заранее поставленные смены
        Map<String, Integer> fixedCount = new HashMap<>();
        for (Fixed f : in.fixed()) {
            if (f.day().isBefore(in.from()) || f.day().isAfter(last)) continue;
            Emp e = emps.get(f.empId());
            String who = e == null ? "#" + f.empId() : e.name();
            if (in.blocked().contains(blockKey(f.empId(), f.day()))) warnings.add(who + ", " + f.day() + ": " + f.pos() + " стоит на день отпуска/больничного/выходного");
            else if (!fits(win, f.empId(), f.day(), f.start(), f.end())) warnings.add(who + ", " + f.day() + ": " + f.pos() + " не совпадает с его заявкой по часам");
            TreeMap<LocalDate, int[]> m = shifts.computeIfAbsent(f.empId(), k -> new TreeMap<>());
            if (m.containsKey(f.day())) {
                warnings.add(who + ", " + f.day() + ": две заранее поставленные смены в один день");
                continue;
            }
            m.put(f.day(), new int[] {f.start(), f.end()});
            weekCount.merge(f.empId(), 1, Integer::sum);
            cells.add(new Cell(f.day(), f.pos(), f.part(), f.start(), f.end(), f.empId(), f.note()));
            fixedCount.merge(f.day() + "|" + f.pos() + "|" + f.part(), 1, Integer::sum);
        }

        // 2) остальное по дням
        List<SlotDef> slots = new ArrayList<>(in.slots());
        slots.sort(Comparator.comparingInt(SlotDef::sort).thenComparing(SlotDef::part));

        for (int i = 0; i < in.days(); i++) {
            LocalDate day = in.from().plusDays(i);
            List<SlotDef> pending = new ArrayList<>();
            for (SlotDef s : slots) {
                int left = s.need() - fixedCount.getOrDefault(day + "|" + s.pos() + "|" + s.part(), 0);
                for (int k = 0; k < left; k++) pending.add(s);
            }
            while (!pending.isEmpty()) {
                SlotDef bestSlot = null;
                List<Emp> bestElig = null;
                List<SlotDef> dead = new ArrayList<>();
                for (SlotDef s : pending) {
                    List<Emp> elig = new ArrayList<>();
                    for (Emp e : emps.values()) {
                        if (eligible(e, s, day, in, win, shifts)) elig.add(e);
                    }
                    if (elig.isEmpty()) {
                        dead.add(s);
                        continue;
                    }
                    if (bestSlot == null || elig.size() < bestElig.size()) {
                        bestSlot = s;
                        bestElig = elig;
                    }
                }
                for (SlotDef s : dead) {
                    pending.remove(s);
                    cells.add(new Cell(day, s.pos(), s.part(), s.start(), s.end(), null, null));
                }
                if (bestSlot == null) break;
                bestElig.sort(Comparator
                    .comparingInt((Emp e) -> weekCount.getOrDefault(e.id(), 0))
                    .thenComparingInt(e -> e.positions().size())
                    .thenComparingLong(Emp::id));
                Emp pick = bestElig.get(0);
                shifts.computeIfAbsent(pick.id(), k -> new TreeMap<>()).put(day, new int[] {bestSlot.start(), bestSlot.end()});
                weekCount.merge(pick.id(), 1, Integer::sum);
                cells.add(new Cell(day, bestSlot.pos(), bestSlot.part(), bestSlot.start(), bestSlot.end(), pick.id(), null));
                pending.remove(bestSlot);
            }
        }
        cells.sort(Comparator.comparing(Cell::day).thenComparing(Cell::start).thenComparing(Cell::pos));
        return new Result(cells, warnings);
    }

    // ------------------------------------------------------------------------------------------------

    /** Проверка одного человека на одну смену (используется и генератором, и при ручных правках). */
    private static boolean eligible(Emp e, SlotDef s, LocalDate day, Input in,
                                    Map<String, List<Window>> win, Map<Long, TreeMap<LocalDate, int[]>> shifts) {
        if (!e.positions().contains(s.pos())) return false;
        TreeMap<LocalDate, int[]> m = shifts.get(e.id());
        if (m != null && m.containsKey(day)) return false;
        if (in.blocked().contains(blockKey(e.id(), day))) return false;
        if (!fits(win, e.id(), day, s.start(), s.end())) return false;
        if (m != null) {
            int[] prev = m.get(day.minusDays(1));
            if (prev != null && s.start() + 1440 - prev[1] < in.minRestMin()) return false;
            int[] next = m.get(day.plusDays(1));
            if (next != null && next[0] + 1440 - s.end() < in.minRestMin()) return false;
            int run = 1;
            for (LocalDate d = day.minusDays(1); m.containsKey(d); d = d.minusDays(1)) run++;
            for (LocalDate d = day.plusDays(1); m.containsKey(d); d = d.plusDays(1)) run++;
            if (run > in.maxConsecutive()) return false;
        }
        return true;
    }

    /** Смена [s, e) не должна пересекать «не могу» и должна лежать внутри «могу только» (если такие окна есть). */
    static boolean fits(Map<String, List<Window>> win, long empId, LocalDate day, int s, int e) {
        for (int off = 0; off <= 1; off++) {
            List<Window> ws = win.get(blockKey(empId, day.plusDays(off)));
            if (ws == null) continue;
            for (Window w : ws) {
                if (w.available()) continue;
                int a = w.from() + off * 1440, b = w.to() + off * 1440;
                if (s < b && e > a) return false;
            }
        }
        List<Window> same = win.get(blockKey(empId, day));
        if (same != null) {
            boolean any = false, ok = false;
            for (Window w : same) {
                if (!w.available()) continue;
                any = true;
                if (s >= w.from() && e <= w.to()) ok = true;
            }
            if (any && !ok) return false;
        }
        return true;
    }

    /** Для ручных правок: список причин, почему человека ставить на эту смену не стоит (пусто = всё чисто). */
    public static List<String> problems(Input in, Emp e, String pos, LocalDate day, int start, int end, Long ignoreShiftOfThatEmpOnDay) {
        List<String> r = new ArrayList<>();
        if (!e.positions().contains(pos)) r.add("не обучен позиции " + pos);
        if (in.blocked().contains(blockKey(e.id(), day))) r.add("в этот день отпуск, больничный или одобренный выходной");
        Map<String, List<Window>> win = new HashMap<>();
        for (Window w : in.windows()) win.computeIfAbsent(blockKey(w.empId(), w.day()), k -> new ArrayList<>()).add(w);
        if (!fits(win, e.id(), day, start, end)) r.add("не совпадает с его заявкой по часам");
        Set<LocalDate> worked = new HashSet<>();
        TreeMap<LocalDate, int[]> m = new TreeMap<>();
        for (Hist h : in.history()) if (h.empId() == e.id() && !h.day().equals(day)) {
            m.put(h.day(), new int[] {h.start(), h.end()});
            worked.add(h.day());
        }
        int[] prev = m.get(day.minusDays(1));
        if (prev != null && start + 1440 - prev[1] < in.minRestMin()) r.add("меньше " + in.minRestMin() / 60 + " ч отдыха после предыдущей смены");
        int[] next = m.get(day.plusDays(1));
        if (next != null && next[0] + 1440 - end < in.minRestMin()) r.add("меньше " + in.minRestMin() / 60 + " ч отдыха до следующей смены");
        int run = 1;
        for (LocalDate d = day.minusDays(1); m.containsKey(d); d = d.minusDays(1)) run++;
        for (LocalDate d = day.plusDays(1); m.containsKey(d); d = d.plusDays(1)) run++;
        if (run > in.maxConsecutive()) r.add("больше " + in.maxConsecutive() + " рабочих дней подряд");
        return r;
    }
}