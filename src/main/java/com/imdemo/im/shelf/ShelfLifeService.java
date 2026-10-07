package com.imdemo.im.shelf;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сроки хранения. Читать могут все вошедшие, править — SUPER_ADMIN и DIRECTOR (см. SecurityConfig). */
@Service
public class ShelfLifeService {

    private static final Set<String> KINDS = Set.of("SHELF", "THAW", "HOLD", "WASH", "DRY");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(мин|час|сут|дн|ден)", Pattern.CASE_INSENSITIVE);

    private final NamedParameterJdbcTemplate db;

    public ShelfLifeService(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    // ---------- чтение + поиск ----------

    /**
     * Вся таблица, сгруппированная по разделам. q — поиск: слова через пробел, каждое должно
     * встретиться в названии продукта, разделе, условии хранения или сроке (регистр и ё/е не важны).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String q, Long sectionId, String zone) {
        List<Map<String, Object>> sections = db.getJdbcTemplate().queryForList(
                "select id, title, note from shelf_life_section order by sort_order, id");
        List<Map<String, Object>> products = db.getJdbcTemplate().queryForList(
                "select id, section_id, name, note from shelf_life_product where active order by sort_order, id");
        List<Map<String, Object>> rules = db.getJdbcTemplate().queryForList(
                "select id, product_id, kind, place, term_text as term, duration_min as \"durationMin\", zone "
                        + "from shelf_life_rule order by sort_order, id");

        Map<Long, List<Map<String, Object>>> rulesByProduct = new HashMap<>();
        for (var r : rules) rulesByProduct.computeIfAbsent(((Number) r.remove("product_id")).longValue(), k -> new ArrayList<>()).add(r);

        List<String> words = words(q);
        Map<Long, List<Map<String, Object>>> productsBySection = new LinkedHashMap<>();
        int total = 0;
        for (var p : products) {
            long pid = ((Number) p.get("id")).longValue();
            long sid = ((Number) p.get("section_id")).longValue();
            if (sectionId != null && sid != sectionId) continue;
            String sectionTitle = (String) sections.stream().filter(s -> ((Number) s.get("id")).longValue() == sid)
                    .findFirst().map(s -> s.get("title")).orElse("");
            List<Map<String, Object>> rs = new ArrayList<>(rulesByProduct.getOrDefault(pid, List.of()));
            if (zone != null && !zone.isBlank()) rs.removeIf(r -> !zone.equalsIgnoreCase((String) r.get("zone")));
            if (rs.isEmpty() && zone != null && !zone.isBlank()) continue;

            if (!words.isEmpty()) {
                String head = norm(p.get("name") + " " + sectionTitle);
                String body = norm(rs.stream().map(r -> r.get("place") + " " + r.get("term")).reduce("", (a, b) -> a + " " + b));
                String all = head + " " + body;
                if (!words.stream().allMatch(all::contains)) continue;
                // подсветка: правила, где слова встретились в самом правиле (или все, если нашли по названию)
                boolean byName = words.stream().allMatch(head::contains);
                for (var r : rs) {
                    String t = norm(r.get("place") + " " + r.get("term"));
                    r.put("hit", !byName && words.stream().anyMatch(t::contains));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", pid);
            out.put("name", p.get("name"));
            out.put("note", p.get("note"));
            out.put("rules", rs);
            productsBySection.computeIfAbsent(sid, k -> new ArrayList<>()).add(out);
            total++;
        }

        List<Map<String, Object>> outSections = new ArrayList<>();
        for (var s : sections) {
            long sid = ((Number) s.get("id")).longValue();
            List<Map<String, Object>> ps = productsBySection.getOrDefault(sid, List.of());
            // в режиме поиска пустые разделы не показываем; без поиска — показываем (там бывают только заметки, как «Зона Cafe»)
            if (ps.isEmpty() && (!words.isEmpty() || (zone != null && !zone.isBlank()) || sectionId != null && sid != sectionId)) continue;
            Map<String, Object> o = new LinkedHashMap<>(s);
            o.put("products", ps);
            outSections.add(o);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("doc", db.getJdbcTemplate().queryForMap(
                "select title, version, cast(doc_date as varchar) as \"docDate\", notice from shelf_life_doc where id = 1"));
        res.put("zones", db.getJdbcTemplate().queryForList(
                "select code, label, temp_text as \"tempText\" from shelf_life_zone order by sort_order"));
        res.put("sections", outSections);
        res.put("total", total);
        res.put("query", q == null ? "" : q);
        return res;
    }

    private static List<String> words(String q) {
        if (q == null || q.isBlank()) return List.of();
        return Arrays.stream(norm(q).split("\\s+")).filter(w -> !w.isBlank()).toList();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е').replace(' ', ' ');
    }

    // ---------- правка (SUPER_ADMIN, DIRECTOR) ----------

    public record DocBody(Integer version, LocalDate docDate, String notice) {}
    public record ProductBody(Long sectionId, String name, String note) {}
    public record RuleBody(String kind, String place, String term, String zone) {}

    @Transactional
    public void updateDoc(DocBody b) {
        if (b.version() == null || b.version() < 1) throw bad("Версия должна быть числом от 1");
        db.update("update shelf_life_doc set version = :v, doc_date = :d, notice = :n where id = 1",
                new MapSqlParameterSource("v", b.version()).addValue("d", b.docDate()).addValue("n", clean(b.notice(), 500)));
    }

    @Transactional
    public long createProduct(ProductBody b) {
        String name = required(b.name(), 200, "Название");
        if (b.sectionId() == null) throw bad("Не выбран раздел");
        ensureExists("shelf_life_section", b.sectionId());
        Long id = db.queryForObject(
                "insert into shelf_life_product(section_id, name, note, sort_order) values (:s, :n, :note, "
                        + "coalesce((select max(sort_order) from shelf_life_product where section_id = :s), 0) + 1) returning id",
                new MapSqlParameterSource("s", b.sectionId()).addValue("n", name).addValue("note", clean(b.note(), 500)), Long.class);
        return id == null ? 0 : id;
    }

    @Transactional
    public void updateProduct(long id, ProductBody b) {
        String name = required(b.name(), 200, "Название");
        int n = db.update("update shelf_life_product set name = :n, note = :note, section_id = coalesce(:s, section_id) where id = :id",
                new MapSqlParameterSource("n", name).addValue("note", clean(b.note(), 500)).addValue("s", b.sectionId()).addValue("id", id));
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @Transactional
    public void deleteProduct(long id) {
        if (db.update("delete from shelf_life_product where id = :id", new MapSqlParameterSource("id", id)) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @Transactional
    public long createRule(long productId, RuleBody b) {
        ensureExists("shelf_life_product", productId);
        var p = ruleParams(b).addValue("pid", productId);
        Long id = db.queryForObject(
                "insert into shelf_life_rule(product_id, kind, place, term_text, duration_min, zone, sort_order) "
                        + "values (:pid, :kind, :place, :term, :dur, :zone, "
                        + "coalesce((select max(sort_order) from shelf_life_rule where product_id = :pid), 0) + 1) returning id", p, Long.class);
        return id == null ? 0 : id;
    }

    @Transactional
    public void updateRule(long id, RuleBody b) {
        var p = ruleParams(b).addValue("id", id);
        int n = db.update("update shelf_life_rule set kind = :kind, place = :place, term_text = :term, duration_min = :dur, zone = :zone where id = :id", p);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @Transactional
    public void deleteRule(long id) {
        if (db.update("delete from shelf_life_rule where id = :id", new MapSqlParameterSource("id", id)) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private MapSqlParameterSource ruleParams(RuleBody b) {
        String place = required(b.place(), 500, "Условие хранения");
        String term = required(b.term(), 200, "Срок");
        String kind = b.kind() == null || b.kind().isBlank() ? "SHELF" : b.kind().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) throw bad("Тип должен быть один из: " + KINDS);
        String zone = b.zone() == null || b.zone().isBlank() ? zoneOf(place) : b.zone().toUpperCase(Locale.ROOT);
        if (zone != null && db.getJdbcTemplate().queryForObject("select count(*) from shelf_life_zone where code = ?", Integer.class, zone) == 0)
            throw bad("Неизвестная зона: " + zone);
        return new MapSqlParameterSource("kind", kind).addValue("place", place).addValue("term", term)
                .addValue("dur", minutes(term)).addValue("zone", zone);
    }

    /** Срок в минутах из текста («24 часа (1 день)» → 1440). Для «Смотреть на бутылке» — null. */
    static Integer minutes(String term) {
        Matcher m = DURATION.matcher(term == null ? "" : term);
        if (!m.find()) return null;
        double n = Double.parseDouble(m.group(1).replace(',', '.'));
        String u = m.group(2).toLowerCase(Locale.ROOT);
        int unit = u.startsWith("мин") ? 1 : u.startsWith("час") ? 60 : 1440;
        return (int) Math.round(n * unit);
    }

    static String zoneOf(String place) {
        String t = place.replace(" ", "");
        if (t.contains("-18")) return "FREEZER";
        if (t.contains("+1…+4") || t.contains("+1+4")) return "COLD";
        if (t.contains("+10…+27") || t.contains("+10+27")) return "ROOM";
        if (t.contains("+20…+25") || t.contains("+20+25")) return "WATER";
        return null;
    }

    private void ensureExists(String table, long id) {
        Integer n = db.getJdbcTemplate().queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
        if (n == null || n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Не найдено");
    }

    private static String required(String s, int max, String what) {
        String c = clean(s, max);
        if (c == null) throw bad(what + " не может быть пустым");
        return c;
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        if (t.length() > max) throw bad("Слишком длинный текст (максимум " + max + " символов)");
        return t;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}