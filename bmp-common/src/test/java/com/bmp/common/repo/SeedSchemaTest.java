package com.bmp.common.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does every {@code INSERT} in {@code seed/dev-seed.sql} name columns that actually exist?
 *
 * <h2>Why this test exists (Session 43)</h2>
 * The seed had been inserting {@code salon_service.updated_at} and {@code salon_staff.status}
 * for many sessions. Neither column has ever existed. Nobody noticed, for three compounding
 * reasons:
 *
 * <ol>
 *   <li><b>The seed is ONE transaction.</b> The first failing statement aborts every statement
 *       after it — {@code current transaction is aborted, commands ignored} — and rolls the lot
 *       back. So one earlier error, a duplicate phone say, masks every later one. You fix the
 *       first, re-run, and discover the second. Then the third.</li>
 *   <li><b>Nothing ran it except a human, by hand, after a reset.</b> A file that only executes
 *       when someone is already debugging something else gets its errors attributed to whatever
 *       they were originally debugging.</li>
 *   <li><b>The docs asserted it worked.</b> {@code seed/README.md} called it idempotent, which
 *       was true only against re-running itself.</li>
 * </ol>
 *
 * The result was demo data that had silently stopped working in a repo that believed it worked.
 * <b>Data that breaks only when you need it is worse than data that breaks loudly</b>, because
 * you reach for it precisely when you're already lost.
 *
 * <h2>Scope, honestly</h2>
 * This is a static parse of the migrations, not a database. It cannot catch type mismatches,
 * constraint violations or bad foreign keys — {@code RUN_LOCALLY.md} §5b covers verifying those
 * for real. It catches the specific, silent, repeated failure above, in milliseconds, with no
 * Docker. A narrow check that always runs beats a broad one that doesn't.
 *
 * <p>The column parser is deliberately forgiving: anything it fails to understand makes a table
 * look like it has FEWER columns, which can only produce a false ALARM, never a false pass. A
 * checker that shouts when confused is useful; one that shrugs is not.
 */
class SeedSchemaTest {

    /** {@code CREATE TABLE schema.name ( ... );} */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.]+)\\s*\\((.*?)\\n\\s*\\);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** {@code ALTER TABLE schema.name ADD COLUMN [IF NOT EXISTS] col ...} */
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ALTER\\s+TABLE\\s+([\\w.]+)\\s+ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z_][\\w]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INSERT_INTO = Pattern.compile(
            "INSERT\\s+INTO\\s+([\\w.]+)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    /** Table-level clauses that are not column definitions. */
    private static final Pattern TABLE_CONSTRAINT = Pattern.compile(
            "(PRIMARY|FOREIGN|UNIQUE|CHECK|CONSTRAINT)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("every seed INSERT names columns that exist in the migrations")
    void seedMatchesSchema() {
        Path seed = RepoLayout.root().resolve("seed/dev-seed.sql");
        assertThat(seed).as("the seed file the whole team relies on after a reset").exists();

        Map<String, Set<String>> tables = columnsFromMigrations();
        assertThat(tables)
                .as("no CREATE TABLE statements found — the parser or the layout has changed, and "
                    + "a check that finds nothing would silently pass forever")
                .isNotEmpty();

        String sql = RepoLayout.read(seed);
        List<String> problems = new ArrayList<>();
        int checked = 0;

        Matcher m = INSERT_INTO.matcher(sql);
        while (m.find()) {
            checked++;
            String table = m.group(1).toLowerCase(Locale.ROOT);
            int line = (int) sql.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;

            Set<String> known = tables.get(table);
            if (known == null) {
                problems.add("  line " + line + ": table `" + table
                        + "` is not created by any migration");
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String raw : m.group(2).split(",")) {
                String col = raw.trim().toLowerCase(Locale.ROOT);
                if (!col.isEmpty() && !known.contains(col)) missing.add(col);
            }
            if (!missing.isEmpty()) {
                problems.add("  line " + line + ": `" + table + "` has no column(s) " + missing
                        + "\n      it has: " + new TreeSet<>(known));
            }
        }

        assertThat(checked)
                .as("no INSERT statements found in the seed — that is itself a problem")
                .isPositive();

        assertThat(problems)
                .as("""
                    Seed data references columns that do not exist.

                    %s

                    The seed runs in a SINGLE TRANSACTION, so the first of these aborts everything
                    after it and rolls the whole file back — leaving a database that looks empty
                    for no visible reason, usually while you are debugging something else.

                    Fix the column list, or add the migration that introduces the column.""",
                        String.join("\n", problems))
                .isEmpty();
    }

    /** {@code schema.table -> {column, ...}}, assembled from CREATE TABLE + ADD COLUMN. */
    private static Map<String, Set<String>> columnsFromMigrations() {
        Map<String, Set<String>> tables = new HashMap<>();

        for (Path service : RepoLayout.serviceDirs()) {
            Path migrations = service.resolve("src/main/resources/db/migration");
            if (!Files.isDirectory(migrations)) continue;

            for (Path file : RepoLayout.filesEndingWith(migrations, ".sql")) {
                String sql = RepoLayout.read(file);

                Matcher create = CREATE_TABLE.matcher(sql);
                while (create.find()) {
                    Set<String> cols = tables.computeIfAbsent(
                            create.group(1).toLowerCase(Locale.ROOT), k -> new HashSet<>());
                    for (String line : create.group(2).split("\n")) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("--")) continue;
                        if (TABLE_CONSTRAINT.matcher(t).lookingAt()) continue;
                        Matcher name = Pattern.compile("([a-zA-Z_][\\w]*)").matcher(t);
                        if (name.lookingAt()) cols.add(name.group(1).toLowerCase(Locale.ROOT));
                    }
                }

                Matcher add = ADD_COLUMN.matcher(sql);
                while (add.find()) {
                    tables.computeIfAbsent(add.group(1).toLowerCase(Locale.ROOT), k -> new HashSet<>())
                          .add(add.group(2).toLowerCase(Locale.ROOT));
                }
            }
        }
        return tables;
    }
}
