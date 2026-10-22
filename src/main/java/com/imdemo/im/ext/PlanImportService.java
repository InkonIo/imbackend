package com.imdemo.im.ext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.imdemo.im.ext.XlsxTables.bad;
import static com.imdemo.im.ext.XlsxTables.cell;
import static com.imdemo.im.ext.XlsxTables.num;

/**
 * Две загрузки Excel для бланка смены:
 *  1) «Прогноз продаж» (листы «Прогноз по дням» и «Прогноз по часам») -> plan_day / plan_hour / plan_staff;
 *  2) «10. Ежечасный сэндвич» (факт блюд по часам за день) -> sandwich_hour.
 * Повторная загрузка за те же дни заменяет прежние данные.
 */
@Service
public class PlanImportService {

    private static final Pattern DM = Pattern.compile("^(\\d{1,2})\\.(\\d{2})(?!\\d).*");
    private final NamedParameterJdbcTemplate jdbc;

    public PlanImportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) throw bad("Файл не передан");
        if (base64.length() > 12_000_000) throw bad("Файл слишком большой");
        try {
            return Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw bad("Файл повреждён");
        }
    }

    private void checkRestaurant(long branchId, String text) {
        String title;
        try {
            title = jdbc.queryForObject("SELECT title FROM ext_branch WHERE id = :b", new MapSqlParameterSource("b", branchId), String.class);
        } catch (EmptyResultDataAccessException e) {
            throw bad("Ресторан не найден");
        }
        String no = XlsxTables.restaurantNo(text);
        if (no != null && title != null && !title.contains(no)) {
            throw bad("Файл по ресторану «" + text + "», а выбран «" + title + "». Открой бланк нужного ресторана.");
        }
    }

    private static java.sql.Date sql(LocalDate d) {
        return java.sql.Date.valueOf(d);
    }

    private static String findIn(List<List<String>> t, String prefixRegex, int maxRows) {
        Pattern p = Pattern.compile(prefixRegex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        for (int i = 0; i < Math.min(maxRows, t.size()); i++) {
            for (String c : t.get(i)) {
                if (c != null && p.matcher(c).find()) return c;
            }
        }
        return null;
    }

    // =====================================================================  ПРОГНОЗ

    private record Day(Integer orders, Double revenue, Double avg) {}

    @Transactional
    public Map<String, Object> importForecast(long branchId, String fileName, String base64) {
        Map<String, List<List<String>>> book = XlsxTables.read(decode(base64));
        List<List<String>> days = null, hours = null;
        for (var e : book.entrySet()) {
            String n = e.getKey().toLowerCase();
            if (n.contains("по дням")) days = e.getValue();
            else if (n.contains("по часам")) hours = e.getValue();
        }
        if (days == null && hours == null) throw bad("Не нашёл листы «Прогноз по дням» / «Прогноз по часам». Это точно файл прогноза?");

        // ресторан и год: «📊 ПРОГНОЗ ПРОДАЖ | 02019 Dostyk Plaza | 12–18 октября 2026»
        List<List<String>> any = days != null ? days : hours;
        String title = findIn(any, "прогноз", 3);
        String restaurant = null;
        int year = LocalDate.now().getYear();
        if (title != null) {
            Matcher y = Pattern.compile("(?<!\\d)(20\\d\\d)(?!\\d)").matcher(title);
            if (y.find()) year = Integer.parseInt(y.group(1));
            Matcher r = Pattern.compile("\\|\\s*(\\d{3,6}[^|]*?)\\s*\\|").matcher(title);
            if (r.find()) restaurant = r.group(1).trim();
        }
        checkRestaurant(branchId, restaurant);

        Map<LocalDate, Day> plan = new TreeMap<>();
        if (days != null) {
            int hdr = -1, cOrd = -1, cRev = -1, cAvg = -1;
            for (int i = 0; i < days.size() && hdr < 0; i++) {
                List<String> r = days.get(i);
                if (!"дата".equalsIgnoreCase(String.valueOf(cell(r, 0)))) continue;
                for (int c = 0; c < r.size(); c++) {
                    String t = r.get(c) == null ? "" : r.get(c).toLowerCase();
                    if (t.startsWith("заказ")) cOrd = c;
                    else if (t.startsWith("выручка")) cRev = c;
                    else if (t.startsWith("ср")) cAvg = c;
                }
                hdr = i;
            }
            if (hdr >= 0) {
                for (int i = hdr + 1; i < days.size(); i++) {
                    List<String> r = days.get(i);
                    String a = cell(r, 0);
                    if (a == null) continue;
                    Matcher m = DM.matcher(a);
                    if (!m.matches()) {
                        if (a.toUpperCase().startsWith("ИТОГО")) break;
                        continue;
                    }
                    LocalDate d = date(year, m);
                    if (d == null) continue;
                    Double o = num(cell(r, cOrd)), rev = num(cell(r, cRev)), av = num(cell(r, cAvg));
                    plan.put(d, new Day(o == null ? null : (int) Math.round(o), rev, av));
                }
            }
        }

        // почасовка: блок «заказы» и блок «выручка» идут рядом, между ними пустая колонка
        Map<LocalDate, Map<Integer, double[]>> hourly = new TreeMap<>();
        Map<LocalDate, Map<String, Double>> staff = new TreeMap<>();
        if (hours != null) {
            int hdr = -1;
            for (int i = 0; i < hours.size(); i++) {
                if ("час".equalsIgnoreCase(String.valueOf(cell(hours.get(i), 0)))) { hdr = i; break; }
            }
            if (hdr >= 0) {
                List<String> h = hours.get(hdr);
                List<List<int[]>> groups = new ArrayList<>(); // каждая группа: [колонка, месяц*100+день]
                int prev = -10;
                for (int c = 1; c < h.size(); c++) {
                    String t = cell(h, c);
                    Matcher m = t == null ? null : DM.matcher(t);
                    if (m == null || !m.matches()) continue;
                    LocalDate d = date(year, m);
                    if (d == null) continue;
                    if (c - prev > 1) groups.add(new ArrayList<>());
                    groups.get(groups.size() - 1).add(new int[]{c, d.getMonthValue() * 100 + d.getDayOfMonth()});
                    prev = c;
                }
                List<int[]> ord = groups.size() > 0 ? groups.get(0) : List.of();
                List<int[]> rev = groups.size() > 1 ? groups.get(1) : List.of();
                int total = -1;
                for (int i = hdr + 1; i < hours.size(); i++) {
                    List<String> r = hours.get(i);
                    String a = cell(r, 0);
                    if (a == null) continue;
                    if (a.toUpperCase().startsWith("ИТОГО")) { total = i; continue; }
                    if (total < 0) {
                        Matcher hm = Pattern.compile("^(\\d{1,2}):\\d{2}").matcher(a);
                        if (!hm.find()) continue;
                        int hour = Integer.parseInt(hm.group(1));
                        for (int[] g : ord) put(hourly, year, g, hour, 0, num(cell(r, g[0])));
                        for (int[] g : rev) put(hourly, year, g, hour, 1, num(cell(r, g[0])));
                    } else {
                        // строки плана людей: «план GCPH», «план часы», «план кол-во», MNG, ночь, длк, C, К, гель
                        if (a.startsWith("❌")) continue;
                        for (int[] g : ord) {
                            Double v = num(cell(r, g[0]));
                            if (v != null) staff.computeIfAbsent(LocalDate.of(year, g[1] / 100, g[1] % 100), k -> new LinkedHashMap<>()).put(a, v);
                        }
                    }
                }
            }
        }

        // если листа по дням нет — дневной план считаем из почасовки
        for (var e : hourly.entrySet()) {
            if (plan.containsKey(e.getKey())) continue;
            double o = 0, rv = 0;
            for (double[] v : e.getValue().values()) { o += v[0]; rv += v[1]; }
            plan.put(e.getKey(), new Day((int) Math.round(o), rv, o > 0 ? rv / o : null));
        }
        if (plan.isEmpty()) throw bad("В файле нет дней с прогнозом");

        LocalDate from = plan.keySet().iterator().next();
        LocalDate to = from;
        for (LocalDate d : plan.keySet()) to = d;
        MapSqlParameterSource rng = new MapSqlParameterSource().addValue("b", branchId).addValue("f", sql(from)).addValue("t", sql(to));
        for (String tbl : List.of("plan_day", "plan_hour", "plan_staff")) {
            jdbc.update("DELETE FROM " + tbl + " WHERE branch_id = :b AND day BETWEEN :f AND :t", rng);
        }
        for (var e : plan.entrySet()) {
            Map<String, Double> st = staff.getOrDefault(e.getKey(), Map.of());
            jdbc.update("INSERT INTO plan_day (branch_id, day, orders, revenue, avg_check, gcph, plan_hours, plan_count) VALUES (:b, :d, :o, :r, :a, :g, :h, :c)",
                    new MapSqlParameterSource().addValue("b", branchId).addValue("d", sql(e.getKey()))
                            .addValue("o", e.getValue().orders()).addValue("r", e.getValue().revenue()).addValue("a", e.getValue().avg())
                            .addValue("g", st.get("план GCPH")).addValue("h", st.get("план часы")).addValue("c", st.get("план кол-во")));
            for (var s : st.entrySet()) {
                jdbc.update("INSERT INTO plan_staff (branch_id, day, label, value) VALUES (:b, :d, :l, :v)",
                        new MapSqlParameterSource().addValue("b", branchId).addValue("d", sql(e.getKey())).addValue("l", s.getKey()).addValue("v", s.getValue()));
            }
        }
        int hrows = 0;
        for (var e : hourly.entrySet()) {
            for (var h : e.getValue().entrySet()) {
                jdbc.update("INSERT INTO plan_hour (branch_id, day, hour, orders, revenue) VALUES (:b, :d, :h, :o, :r)",
                        new MapSqlParameterSource().addValue("b", branchId).addValue("d", sql(e.getKey())).addValue("h", h.getKey())
                                .addValue("o", (int) Math.round(h.getValue()[0])).addValue("r", h.getValue()[1]));
                hrows++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restaurant", restaurant);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("days", plan.size());
        out.put("hourRows", hrows);
        out.put("staffDays", staff.size());
        return out;
    }

    private static LocalDate date(int year, Matcher m) {
        try {
            return LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (Exception e) {
            return null;
        }
    }

    private static void put(Map<LocalDate, Map<Integer, double[]>> out, int year, int[] g, int hour, int idx, Double v) {
        if (v == null) return;
        LocalDate d = LocalDate.of(year, g[1] / 100, g[1] % 100);
        out.computeIfAbsent(d, k -> new TreeMap<>()).computeIfAbsent(hour, k -> new double[2])[idx] = v;
    }

    // =====================================================================  СЭНДВИЧИ

    @Transactional
    public Map<String, Object> importSandwich(long branchId, String fileName, String base64) {
        Map<String, List<List<String>>> book = XlsxTables.read(decode(base64));
        List<List<String>> t = book.values().iterator().next();

        String rs = findIn(t, "название ресторана", 6);
        String restaurant = rs == null ? null : rs.substring(rs.indexOf(':') + 1).trim();
        checkRestaurant(branchId, restaurant);

        String ds = findIn(t, "^\\s*дата\\s*:", 8);
        LocalDate day = null;
        if (ds != null) {
            Matcher m = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})").matcher(ds);
            if (m.find()) {
                day = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
                if (m.find()) throw bad("В отчёте несколько дней. Выгрузи по одному дню: «Дата: 13.10.2026».");
            }
        }
        if (day == null) throw bad("Не нашёл «Дата: дд.мм.гггг» в шапке. Это точно отчёт «Ежечасный сэндвич»?");

        int hdr = -1, cHour = -1, cDish = -1, cQty = -1;
        for (int i = 0; i < t.size() && hdr < 0; i++) {
            List<String> r = t.get(i);
            for (int c = 0; c < r.size(); c++) {
                String x = r.get(c) == null ? "" : r.get(c).toLowerCase();
                if (x.contains("час закрытия")) cHour = c;
                else if (x.equals("блюдо")) cDish = c;
                else if (x.contains("количество блюд")) cQty = c;
            }
            if (cHour >= 0 && cQty >= 0) hdr = i;
        }
        if (hdr < 0) throw bad("Не нашёл колонки «Час закрытия» и «Количество блюд».");

        Map<Integer, Integer> byHour = new TreeMap<>();
        Integer cur = null;
        for (int i = hdr + 1; i < t.size(); i++) {
            List<String> r = t.get(i);
            String hc = cell(r, cHour);
            if (hc != null) {
                if (hc.toLowerCase().contains("всего")) { cur = null; continue; }
                Double hv = num(hc);
                if (hv != null) cur = (int) Math.round(hv);
            }
            if (cur == null || cell(r, cDish) == null) continue; // строки «N всего» и итог ресторана пропускаем
            Double q = num(cell(r, cQty));
            if (q != null) byHour.merge(cur, (int) Math.round(q), Integer::sum);
        }
        if (byHour.isEmpty()) throw bad("В файле нет блюд по часам");

        jdbc.update("DELETE FROM sandwich_hour WHERE branch_id = :b AND day = :d",
                new MapSqlParameterSource().addValue("b", branchId).addValue("d", sql(day)));
        int total = 0;
        for (var e : byHour.entrySet()) {
            jdbc.update("INSERT INTO sandwich_hour (branch_id, day, hour, qty) VALUES (:b, :d, :h, :q)",
                    new MapSqlParameterSource().addValue("b", branchId).addValue("d", sql(day)).addValue("h", e.getKey()).addValue("q", e.getValue()));
            total += e.getValue();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restaurant", restaurant);
        out.put("day", day.toString());
        out.put("hours", byHour.size());
        out.put("total", total);
        return out;
    }
}