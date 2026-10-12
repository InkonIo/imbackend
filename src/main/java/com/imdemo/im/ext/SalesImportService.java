package com.imdemo.im.ext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imdemo.im.web.error.ApiException;

/**
 * Загрузка отчёта «1. Почасовая выручка (Отчёт инсайда)» из Excel: день, час, гости (GC), сумма, средний чек.
 * xlsx разбирается средствами JDK (zip + StAX), отдельные библиотеки не нужны.
 * Повторная загрузка за те же дни заменяет прежние данные.
 */
@Service
public class SalesImportService {

    private static final long MAX_ENTRY = 30L * 1024 * 1024;

    private final NamedParameterJdbcTemplate jdbc;

    public SalesImportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(LocalDate day, int hour, int guests, double sales, Double avg) {}

    private static ApiException bad(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, m);
    }

    @Transactional
    public Map<String, Object> importXlsx(long branchId, Long userId, String fileName, String base64) {
        if (base64 == null || base64.isBlank()) throw bad("Файл не передан");
        if (base64.length() > 12_000_000) throw bad("Файл слишком большой");
        String title;
        try {
            title = jdbc.queryForObject("SELECT title FROM ext_branch WHERE id = :b", new MapSqlParameterSource("b", branchId), String.class);
        } catch (EmptyResultDataAccessException e) {
            throw bad("Ресторан не найден");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw bad("Файл повреждён");
        }

        Parsed parsed = parse(bytes);
        String restaurant = parsed.restaurant();
        Map<String, Row> rows = parsed.rows();

        // защита от загрузки отчёта чужого ресторана: номер из названия отчёта («02019 Dostyk Plaza») должен быть в названии ресторана
        if (restaurant != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*0*(\\d+)").matcher(restaurant);
            if (m.find() && title != null && !title.contains(m.group(1))) {
                throw bad("Отчёт по ресторану «" + restaurant + "», а выбран «" + title + "». Выберите нужный ресторан справа вверху.");
            }
        }

        LocalDate from = null, to = null;
        long guestsSum = 0;
        double salesSum = 0;
        for (Row r : rows.values()) {
            if (from == null || r.day.isBefore(from)) from = r.day;
            if (to == null || r.day.isAfter(to)) to = r.day;
            guestsSum += r.guests;
            salesSum += r.sales;
        }

        Long importId = jdbc.queryForObject("""
            INSERT INTO sales_import (branch_id, file_name, restaurant, from_day, to_day, rows, guests, sales, imported_by)
            VALUES (:b, :fn, :rs, :f, :t, :n, :g, :s, :u) RETURNING id
            """, new MapSqlParameterSource().addValue("b", branchId).addValue("fn", fileName).addValue("rs", restaurant)
                .addValue("f", java.sql.Date.valueOf(from)).addValue("t", java.sql.Date.valueOf(to))
                .addValue("n", rows.size()).addValue("g", guestsSum).addValue("s", salesSum).addValue("u", userId), Long.class);

        int replaced = jdbc.update("DELETE FROM sales_hour WHERE branch_id = :b AND day BETWEEN :f AND :t",
            new MapSqlParameterSource().addValue("b", branchId).addValue("f", java.sql.Date.valueOf(from)).addValue("t", java.sql.Date.valueOf(to)));

        MapSqlParameterSource[] batch = new MapSqlParameterSource[rows.size()];
        int k = 0;
        for (Row r : rows.values()) {
            batch[k++] = new MapSqlParameterSource().addValue("b", branchId).addValue("d", java.sql.Date.valueOf(r.day))
                .addValue("h", r.hour).addValue("g", r.guests).addValue("s", r.sales).addValue("a", r.avg).addValue("i", importId);
        }
        jdbc.batchUpdate("INSERT INTO sales_hour (branch_id, day, hour, guests, sales, avg_check, import_id) VALUES (:b, :d, :h, :g, :s, :a, :i)", batch);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restaurant", restaurant);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("days", rows.values().stream().map(r -> r.day).distinct().count());
        out.put("rows", rows.size());
        out.put("guests", guestsSum);
        out.put("sales", Math.round(salesSum));
        out.put("replaced", replaced);
        return out;
    }

    private record Parsed(String restaurant, Map<String, Row> rows) {}

    private static Parsed parse(byte[] bytes) {
        List<List<String>> table = readSheet(bytes);
        String restaurant = null;
        for (List<String> r : table) {
            for (String c : r) {
                if (c != null && c.startsWith("Название ресторана:")) restaurant = c.substring(c.indexOf(':') + 1).trim();
            }
            if (restaurant != null) break;
        }
        int hdr = -1, cDay = -1, cHour = -1, cGuests = -1, cSales = -1, cAvg = -1;
        for (int i = 0; i < table.size() && hdr < 0; i++) {
            List<String> r = table.get(i);
            for (int c = 0; c < r.size(); c++) {
                String t = r.get(c) == null ? "" : r.get(c).toLowerCase();
                if (t.contains("количество гостей")) cGuests = c;
                else if (t.contains("учетный день") || t.contains("учётный день")) cDay = c;
                else if (t.contains("час закрытия")) cHour = c;
                else if (t.contains("сумма без скидки")) cSales = c;
                else if (t.contains("средняя сумма")) cAvg = c;
            }
            if (cGuests >= 0 && cDay >= 0 && cHour >= 0) hdr = i;
        }
        if (hdr < 0) throw bad("Не нашёл колонки «Учётный день», «Час закрытия», «Количество гостей». Это точно отчёт «Почасовая выручка»?");

        Map<String, Row> rows = new LinkedHashMap<>();
        LocalDate cur = null;
        for (int i = hdr + 1; i < table.size(); i++) {
            List<String> r = table.get(i);
            String dc = get(r, cDay);
            if (dc != null) {
                if (dc.toLowerCase().contains("всего")) continue;
                LocalDate d = parseDay(dc);
                if (d != null) cur = d;
                else continue;
            }
            Integer hour = parseInt(get(r, cHour));
            Double guests = parseNum(get(r, cGuests));
            if (cur == null || hour == null || guests == null || hour < 0 || hour > 23) continue;
            double sales = nz(parseNum(get(r, cSales)));
            Double avg = parseNum(get(r, cAvg));
            String key = cur + "|" + hour;
            Row old = rows.get(key);
            if (old != null) rows.put(key, new Row(cur, hour, old.guests + (int) Math.round(guests), old.sales + sales, avg));
            else rows.put(key, new Row(cur, hour, (int) Math.round(guests), sales, avg));
        }
        if (rows.isEmpty()) throw bad("В файле нет строк с гостями по часам");

        return new Parsed(restaurant, rows);
    }

    // ------------------------------------------------------------ разбор значений

    private static String get(List<String> r, int c) {
        if (c < 0 || c >= r.size()) return null;
        String v = r.get(c);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    private static Double parseNum(String s) {
        if (s == null) return null;
        try {
            return Double.parseDouble(s.replace(',', '.').replace(" ", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        Double d = parseNum(s);
        return d == null ? null : (int) Math.round(d);
    }

    private static LocalDate parseDay(String s) {
        if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
            return LocalDate.of(Integer.parseInt(s.substring(6)), Integer.parseInt(s.substring(3, 5)), Integer.parseInt(s.substring(0, 2)));
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return LocalDate.parse(s.substring(0, 10));
        Double serial = parseNum(s); // Excel хранит дату числом: дни от 30.12.1899
        if (serial != null && serial > 20000 && serial < 80000) return LocalDate.of(1899, 12, 30).plusDays(serial.longValue());
        return null;
    }

    // ------------------------------------------------------------ xlsx без библиотек

    private static List<List<String>> readSheet(byte[] bytes) {
        byte[] sst = null, sheet = null;
        String sheetName = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml")) sst = readLimited(z);
                else if (n.matches("xl/worksheets/sheet\\d+\\.xml") && (sheetName == null || n.compareTo(sheetName) < 0)) {
                    sheet = readLimited(z);
                    sheetName = n;
                }
            }
        } catch (IOException e) {
            throw bad("Не удалось прочитать файл: это должен быть .xlsx");
        }
        if (sheet == null) throw bad("В файле нет листа с данными. Нужен .xlsx");
        try {
            List<String> strings = sst == null ? List.of() : readStrings(sst);
            return readCells(sheet, strings);
        } catch (Exception e) {
            throw bad("Не удалось разобрать таблицу: " + e.getMessage());
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY) throw new IOException("слишком большой файл");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }

    private static List<String> readStrings(byte[] xml) throws Exception {
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        List<String> out = new ArrayList<>();
        StringBuilder sb = null;
        int skip = 0;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("si")) sb = new StringBuilder();
                else if (n.equals("rPh")) skip++;
                else if (n.equals("t") && sb != null) {
                    String t = r.getElementText();
                    if (skip == 0) sb.append(t);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("rPh")) skip--;
                else if (n.equals("si") && sb != null) { out.add(sb.toString()); sb = null; }
            }
        }
        return out;
    }

    private static int colIndex(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            c = c * 26 + (ch - 'A' + 1);
        }
        return c - 1;
    }

    private static List<List<String>> readCells(byte[] xml, List<String> strings) throws Exception {
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        List<List<String>> out = new ArrayList<>();
        TreeMap<Integer, String> row = null;
        int col = 0;
        String type = null, val = null;
        StringBuilder inl = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> row = new TreeMap<>();
                    case "c" -> {
                        String ref = r.getAttributeValue(null, "r");
                        col = ref == null ? (row == null || row.isEmpty() ? 0 : row.lastKey() + 1) : colIndex(ref);
                        type = r.getAttributeValue(null, "t");
                        val = null;
                        inl = null;
                    }
                    case "v" -> val = r.getElementText();
                    case "is" -> inl = new StringBuilder();
                    case "t" -> { if (inl != null) inl.append(r.getElementText()); }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("c") && row != null) {
                    String v = val;
                    if ("s".equals(type) && val != null) {
                        int idx = Integer.parseInt(val.trim());
                        v = idx >= 0 && idx < strings.size() ? strings.get(idx) : null;
                    } else if ("inlineStr".equals(type) && inl != null) v = inl.toString();
                    if (v != null) row.put(col, v);
                } else if (n.equals("row") && row != null) {
                    int max = row.isEmpty() ? -1 : row.lastKey();
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i <= max; i++) list.add(row.get(i));
                    out.add(list);
                    row = null;
                }
            }
        }
        return out;
    }
}