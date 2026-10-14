package com.imdemo.im.ext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Разбор Excel «Расписание менеджеров» от директора. Без библиотек (zip + StAX), только чтение.
 *
 * Как устроен файл: один лист = одна неделя. В шапке дни и даты, дальше у каждого человека две строки:
 * верхняя — имя и смены по дням, нижняя — должность и заметки под клеткой (например «Ночь МЕ 00:00»).
 * В клетке: «ЧЧ:ММ-ЧЧ:ММ», перенос строки, позиция и/или задача. OFF / OFF** — выходной.
 *
 * Часть дня определяется по времени начала так же, как в графике сотрудников (partOf):
 * до 11:00 — утро, 11–15 — «промеж», с 15 — вечер, с 21 и до 4 — ночь.
 */
public final class ManagerScheduleParser {

    private ManagerScheduleParser() {}

    public record Cell(int day, String date, String kind, String start, String end, String part, String pos, String note, String raw, String warn) {}

    public record Person(String name, String title, List<Cell> cells) {}

    public record Week(String sheet, String weekStart, List<String> dayNotes, List<Person> people, List<String> warnings) {}

    /** Значение ячейки: текст и признак «это число» (даты и время Excel хранит числами). */
    record X(String v, boolean num) {}

    private static final long MAX_ENTRY = 30L * 1024 * 1024;
    private static final Pattern RANGE = Pattern.compile("^(\\d{1,2})[:.](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[:.](\\d{2})(.*)$", Pattern.DOTALL);
    private static final Pattern START_ONLY = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::\\d{2})?(.*)$", Pattern.DOTALL);
    private static final Pattern OFF = Pattern.compile("^(?iu:OFF|вых(?:одной)?\\.?)\\**$");
    private static final Pattern POS = Pattern.compile("^(INS|ИНС|[КK]|[СC])(?=$|[\\s.,/\\\\])(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("(?iu)(подпись|директор|заместитель|менеджер|обучени)");

    // ------------------------------------------------------------------ публичное

    public static List<Week> parse(byte[] xlsx) {
        List<Week> out = new ArrayList<>();
        for (Map.Entry<String, List<List<X>>> s : readWorkbook(xlsx).entrySet()) {
            Week w = parseSheet(s.getKey().trim(), s.getValue());
            if (w != null) out.add(w);
        }
        return out;
    }

    static int partStartMin(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    static String hhmm(int min) {
        min = ((min % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", min / 60, min % 60);
    }

    /** Та же граница частей дня, что в SchedPlanService.partOf. */
    static String partOf(int start) {
        if (start >= 1260 || start < 240) return "NIGHT";
        if (start < 660) return "MORNING";
        if (start < 900) return "MID";
        return "EVENING";
    }

    // ------------------------------------------------------------------ лист

    private static String txt(List<List<X>> rows, int r, int c) {
        if (r < 0 || r >= rows.size()) return "";
        List<X> row = rows.get(r);
        if (c >= row.size() || row.get(c) == null) return "";
        return clean(row.get(c).v());
    }

    private static X raw(List<List<X>> rows, int r, int c) {
        if (r < 0 || r >= rows.size()) return null;
        List<X> row = rows.get(r);
        return c < row.size() ? row.get(c) : null;
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static Week parseSheet(String name, List<List<X>> rows) {
        int hdr = -1;
        for (int r = 0; r < Math.min(rows.size(), 12); r++) {
            if (txt(rows, r, 1).toLowerCase(Locale.ROOT).startsWith("понедельник")) { hdr = r; break; }
        }
        if (hdr < 0) return null; // не лист с расписанием
        List<String> warnings = new ArrayList<>();

        LocalDate[] dates = new LocalDate[7];
        for (int i = 0; i < 7; i++) dates[i] = toDate(raw(rows, hdr + 1, 1 + i));
        LocalDate monday = dates[0];
        if (monday == null) {
            Matcher m = Pattern.compile("^(\\d{2})\\.(\\d{2})").matcher(name);
            if (m.find()) {
                try {
                    monday = LocalDate.of(LocalDate.now().getYear(), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
                    warnings.add("В шапке нет дат, неделя взята из названия листа: " + monday);
                } catch (Exception ignored) { /* ниже поймаем */ }
            }
        }
        if (monday == null) return null;
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            warnings.add("Первая дата недели " + monday + " не понедельник — проверьте шапку листа.");
        }
        for (int i = 0; i < 7; i++) if (dates[i] == null) dates[i] = monday.plusDays(i);

        List<String> dayNotes = new ArrayList<>();
        for (int i = 0; i < 7; i++) dayNotes.add(hdr >= 2 ? txt(rows, hdr - 1, 1 + i) : "");

        List<Person> people = new ArrayList<>();
        int r = hdr + 2;
        while (r < rows.size()) {
            String a = txt(rows, r, 0);
            if (a.toLowerCase(Locale.ROOT).startsWith("дата")) break;
            if (a.isEmpty() || TITLE.matcher(a).find() && a.toLowerCase(Locale.ROOT).contains("подпись")) { r++; continue; }
            // строка с именем; следующая — должность и заметки под клеткой
            String title = txt(rows, r + 1, 0);
            boolean hasTitle = !title.isEmpty() && (title.toLowerCase(Locale.ROOT).contains("подпись") || TITLE.matcher(title).find());
            if (!hasTitle && TITLE.matcher(a).find()) { r++; continue; }
            String role = hasTitle ? title.replaceAll("(?iu)\\s*подпись.*$", "").trim() : "";

            List<Cell> cells = new ArrayList<>();
            List<String> extras = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                extras.add(hasTitle ? txt(rows, r + 1, 1 + i) : "");
            }
            for (int i = 0; i < 7; i++) {
                Cell c = parseCell(i, dates[i], raw(rows, r, 1 + i));
                String ex = extras.get(i);
                if (!ex.isEmpty()) {
                    if ("SHIFT".equals(c.kind())) {
                        c = new Cell(c.day(), c.date(), c.kind(), c.start(), c.end(), c.part(), c.pos(),
                            join(c.note(), ex), c.raw(), c.warn());
                    } else {
                        warnings.add(a + ", " + shortDate(dates[i]) + ": заметка под клеткой «" + ex + "» не привязана к смене (клетка не рабочая)");
                    }
                }
                cells.add(c);
            }
            // «Хорошего отпуска!» обычно растянуто на всю неделю: пустые клетки после него — тоже отпуск
            boolean vac = false;
            for (int i = 0; i < 7; i++) {
                Cell c = cells.get(i);
                if ("VACATION".equals(c.kind())) vac = true;
                else if (vac && "EMPTY".equals(c.kind())) {
                    cells.set(i, new Cell(c.day(), c.date(), "VACATION", null, null, null, null, null, "", null));
                } else vac = false;
            }
            people.add(new Person(a, role, cells));
            r += hasTitle ? 2 : 1;
        }
        if (people.isEmpty()) return null;
        return new Week(name, monday.toString(), dayNotes, people, warnings);
    }

    private static String shortDate(LocalDate d) {
        return String.format("%02d.%02d", d.getDayOfMonth(), d.getMonthValue());
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + " · " + b;
    }

    // ------------------------------------------------------------------ клетка

    static Cell parseCell(int day, LocalDate date, X x) {
        String d = date.toString();
        if (x == null || x.v() == null || clean(x.v()).isEmpty()) return new Cell(day, d, "EMPTY", null, null, null, null, null, "", null);

        // чистое время без конца (Excel хранит долей суток): считаем началом смены
        if (x.num()) {
            try {
                double v = Double.parseDouble(x.v().trim());
                if (v > 0 && v < 1) {
                    int min = (int) Math.round(v * 1440);
                    return shift(day, d, min, min + 510, null, hhmm(min), "Указано только время начала — конец поставлен +8:30, проверьте");
                }
            } catch (NumberFormatException ignored) { /* дальше как текст */ }
        }

        String s = clean(x.v());
        if (OFF.matcher(s).matches()) return new Cell(day, d, "OFF", null, null, null, null, null, s, null);
        if (s.toLowerCase(Locale.ROOT).contains("отпуск")) return new Cell(day, d, "VACATION", null, null, null, null, null, s, null);

        Matcher m = RANGE.matcher(s);
        if (m.matches()) {
            int st = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            int en = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
            return shift(day, d, st, en, m.group(5), s, null);
        }
        Matcher m2 = START_ONLY.matcher(s);
        if (m2.matches()) {
            int st = Integer.parseInt(m2.group(1)) * 60 + Integer.parseInt(m2.group(2));
            return shift(day, d, st, st + 510, m2.group(3), s, "Указано только время начала — конец поставлен +8:30, проверьте");
        }
        // текст без времени — задача, в график часов не попадает
        return new Cell(day, d, "TASK", null, null, null, null, s, s, "Нет времени — в график не попадёт, добавьте вручную");
    }

    private static Cell shift(int day, String d, int st, int en, String rest, String rawText, String warn) {
        if (st < 0 || st >= 1440 || en < 0) return new Cell(day, d, "TASK", null, null, null, null, rawText, rawText, "Непонятное время");
        if (en == 1439) en = 1440;
        if (en <= st) en += 1440;
        String body = rest == null ? "" : rest.replace(' ', ' ').replaceAll("\\s+", " ").replaceFirst("^[\\s|/\\\\,.\\-–+]+", "").trim();
        String pos = "MNGR";
        String note = body;
        Matcher pm = POS.matcher(body);
        if (pm.matches()) {
            String code = pm.group(1).toUpperCase(Locale.ROOT);
            pos = code.startsWith("INS") || code.startsWith("ИНС") ? "INS" : code.equals("К") || code.equals("K") ? "K" : "C";
            note = pm.group(2).replaceFirst("^[\\s|/\\\\,.\\-–+]+", "").trim();
        }
        return new Cell(day, d, "SHIFT", hhmm(st), hhmm(en), partOf(st), pos, note.isEmpty() ? null : note, rawText, warn);
    }

    // ------------------------------------------------------------------ даты

    private static LocalDate toDate(X x) {
        if (x == null || x.v() == null) return null;
        String s = x.v().trim();
        try {
            if (x.num()) {
                double v = Double.parseDouble(s);
                if (v > 20000 && v < 80000) return LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(v));
                return null;
            }
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
                return LocalDate.of(Integer.parseInt(s.substring(6)), Integer.parseInt(s.substring(3, 5)), Integer.parseInt(s.substring(0, 2)));
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return LocalDate.parse(s.substring(0, 10));
        } catch (Exception ignored) { /* не дата */ }
        return null;
    }

    // ------------------------------------------------------------------ xlsx

    private static Map<String, List<List<X>>> readWorkbook(byte[] bytes) {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml") || n.equals("xl/workbook.xml") || n.equals("xl/_rels/workbook.xml.rels")
                    || n.matches("xl/worksheets/[^/]+\\.xml")) {
                    files.put(n, readLimited(z));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось прочитать файл: это должен быть .xlsx");
        }
        if (!files.containsKey("xl/workbook.xml")) throw new IllegalArgumentException("Это не похоже на файл Excel (.xlsx)");
        try {
            List<String> strings = files.containsKey("xl/sharedStrings.xml") ? readStrings(files.get("xl/sharedStrings.xml")) : List.of();
            Map<String, String> rels = new HashMap<>();
            if (files.containsKey("xl/_rels/workbook.xml.rels")) {
                XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(files.get("xl/_rels/workbook.xml.rels")));
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                        rels.put(r.getAttributeValue(null, "Id"), r.getAttributeValue(null, "Target"));
                    }
                }
            }
            Map<String, List<List<X>>> out = new LinkedHashMap<>();
            XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(files.get("xl/workbook.xml")));
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                    String name = r.getAttributeValue(null, "name");
                    String rid = null;
                    for (int i = 0; i < r.getAttributeCount(); i++) if (r.getAttributeLocalName(i).equals("id")) rid = r.getAttributeValue(i);
                    String target = rid == null ? null : rels.get(rid);
                    if (target == null) continue;
                    String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    byte[] xml = files.get(path);
                    if (xml != null) out.put(name == null ? path : name, readCells(xml, strings));
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("Не удалось разобрать таблицу: " + e.getMessage());
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

    private static int rowIndex(String ref) {
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) i++;
        try { return Integer.parseInt(ref.substring(i)) - 1; } catch (NumberFormatException e) { return -1; }
    }

    /** Строки по номеру (пустые строки сохраняются), ячейки по номеру столбца до H включительно (с запасом — до V). */
    private static List<List<X>> readCells(byte[] xml, List<String> strings) throws Exception {
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        TreeMap<Integer, TreeMap<Integer, X>> grid = new TreeMap<>();
        TreeMap<Integer, X> row = null;
        int rowNo = 0, col = 0;
        String type = null, val = null;
        StringBuilder inl = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        String rn = r.getAttributeValue(null, "r");
                        rowNo = rn == null ? (grid.isEmpty() ? 0 : grid.lastKey() + 1) : Integer.parseInt(rn) - 1;
                        row = new TreeMap<>();
                    }
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
                    boolean num = type == null || type.equals("n");
                    if ("s".equals(type) && val != null) {
                        int idx = Integer.parseInt(val.trim());
                        v = idx >= 0 && idx < strings.size() ? strings.get(idx) : null;
                    } else if ("inlineStr".equals(type) && inl != null) v = inl.toString();
                    if (v != null) row.put(col, new X(v, num));
                } else if (n.equals("row") && row != null) {
                    grid.put(rowNo, row);
                    row = null;
                }
            }
        }
        List<List<X>> out = new ArrayList<>();
        int last = grid.isEmpty() ? -1 : grid.lastKey();
        for (int i = 0; i <= last; i++) {
            TreeMap<Integer, X> rr = grid.get(i);
            List<X> list = new ArrayList<>();
            if (rr != null && !rr.isEmpty()) for (int c = 0; c <= rr.lastKey(); c++) list.add(rr.get(c));
            out.add(list);
        }
        return out;
    }
}