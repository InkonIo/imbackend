package com.imdemo.im.template.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Жёсткое удаление строки вместе со всем, что на неё ссылается (смены, пункты смен, фото, отметки…).
 * Схему не нужно знать заранее: связи берутся из внешних ключей PostgreSQL, и удаление идёт от самых
 * дальних таблиц к ближним. Связи ON DELETE SET NULL / SET DEFAULT не трогаем (база сама обнулит ссылку).
 * Вызывать внутри транзакции: либо удалится всё, либо ничего.
 */
@Service
@RequiredArgsConstructor
public class TemplateForceDelete {

    private final JdbcTemplate jdbc;

    private record Fk(String child, String childCols, String parentCols) {}

    private static final String FK_SQL = """
            SELECT c.conrelid::regclass::text AS child,
                   (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY k.ord)
                      FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS child_cols,
                   (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY k.ord)
                      FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord)
                      JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum) AS parent_cols
              FROM pg_constraint c
             WHERE c.contype = 'f' AND c.confrelid = ?::regclass AND c.confdeltype NOT IN ('n', 'd')
            """;

    private List<Fk> children(String table) {
        return jdbc.query(FK_SQL, (rs, i) -> new Fk(rs.getString("child"), rs.getString("child_cols"), rs.getString("parent_cols")), table);
    }

    /**
     * @param table  таблица, откуда удаляем (имя как в базе)
     * @param where  условие отбора строк, например «id = 5» (только свои значения, не ввод пользователя)
     * @param dryRun true: только посчитать, что будет удалено; false: удалить
     * @return сколько строк в каких таблицах (при подсчёте одна строка могла дойти двумя путями, цифры «около»)
     */
    public Map<String, Long> run(String table, String where, boolean dryRun) {
        Map<String, Long> out = new LinkedHashMap<>();
        walk(table, where, new ArrayList<>(List.of(table)), dryRun, out);
        return out;
    }

    private void walk(String table, String where, List<String> path, boolean dry, Map<String, Long> out) {
        for (Fk fk : children(table)) {
            if (fk.child().equals(table) || path.contains(fk.child())) continue; // сам на себя и циклы пропускаем
            String childWhere = "(" + fk.childCols() + ") IN (SELECT " + fk.parentCols() + " FROM " + table + " WHERE " + where + ")";
            path.add(fk.child());
            walk(fk.child(), childWhere, path, dry, out);
            path.remove(path.size() - 1);
        }
        if (dry) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
            if (n != null && n > 0) out.merge(table, n, Math::max);
        } else {
            int n = jdbc.update("DELETE FROM " + table + " WHERE " + where);
            if (n > 0) out.merge(table, (long) n, Long::sum);
        }
    }
}