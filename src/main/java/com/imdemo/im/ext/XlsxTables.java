package com.imdemo.im.ext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.springframework.http.HttpStatus;

import com.imdemo.im.web.error.ApiException;

/** Читает все листы .xlsx (zip + StAX, без библиотек): имя листа -> строки -> ячейки. */
final class XlsxTables {

    private static final long MAX_ENTRY = 30L * 1024 * 1024;

    private XlsxTables() {}

    static Map<String, List<List<String>>> read(byte[] bytes) {
        byte[] sst = null, wb = null, rels = null;
        Map<String, byte[]> sheets = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml")) sst = readLimited(z);
                else if (n.equals("xl/workbook.xml")) wb = readLimited(z);
                else if (n.equals("xl/_rels/workbook.xml.rels")) rels = readLimited(z);
                else if (n.matches("xl/worksheets/sheet\\d+\\.xml")) sheets.put(n, readLimited(z));
            }
        } catch (IOException e) {
            throw bad("Не удалось прочитать файл: это должен быть .xlsx");
        }
        if (sheets.isEmpty()) throw bad("В файле нет листов. Нужен .xlsx");
        try {
            List<String> strings = sst == null ? List.of() : readStrings(sst);
            // имя листа -> файл (через workbook.xml и его rels)
            Map<String, String> target = new LinkedHashMap<>(); // rId -> путь
            if (rels != null) {
                XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(rels));
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                        String t = r.getAttributeValue(null, "Target");
                        if (t != null) target.put(r.getAttributeValue(null, "Id"), t.startsWith("/") ? t.substring(1) : "xl/" + t);
                    }
                }
            }
            Map<String, List<List<String>>> out = new LinkedHashMap<>();
            if (wb != null) {
                XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(wb));
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                        String name = r.getAttributeValue(null, "name");
                        String rid = null;
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            if (r.getAttributeLocalName(i).equals("id")) rid = r.getAttributeValue(i);
                        }
                        byte[] data = rid == null ? null : sheets.get(target.get(rid));
                        if (name != null && data != null) out.put(name, readCells(data, strings));
                    }
                }
            }
            if (out.isEmpty()) { // нет workbook.xml: берём листы по порядку
                int i = 1;
                for (byte[] d : sheets.values()) out.put("Лист" + i++, readCells(d, strings));
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad("Не удалось разобрать таблицу: " + e.getMessage());
        }
    }

    static ApiException bad(String m) {
        return new ApiException(HttpStatus.BAD_REQUEST, m);
    }

    static String cell(List<String> r, int c) {
        if (r == null || c < 0 || c >= r.size()) return null;
        String v = r.get(c);
        return v == null || v.isBlank() ? null : v.trim();
    }

    static Double num(String s) {
        if (s == null) return null;
        try {
            return Double.parseDouble(s.replace(',', '.').replace(" ", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Достаёт номер ресторана из «02019 Dostyk Plaza» (без нулей впереди). */
    static String restaurantNo(String restaurant) {
        if (restaurant == null) return null;
        Matcher m = Pattern.compile("^\\s*0*(\\d+)").matcher(restaurant);
        return m.find() ? m.group(1) : null;
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