package cn.lunadeer.dominion.utils.databse;

import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.FIelds.*;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import cn.lunadeer.dominion.utils.databse.syntax.Show.Show;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table-level CSV backup.
 *
 * Format: first line column names, second line unified type names, then one
 * record per row. Fields are quoted (RFC 4180 style) whenever they contain a
 * comma, quote, CR/LF or leading/trailing whitespace. SQL NULL is written as
 * an unquoted \N marker; a literal "\N" in data is force-quoted.
 *
 * Restore is schema-drift tolerant: CSV columns missing in the current table
 * are skipped with a warning; columns missing in the CSV keep their database
 * defaults. This lets backups taken before a schema upgrade be restored
 * after it (and vice versa).
 *
 * Known accepted ambiguity: a literal unquoted \N string value in a
 * PRE-REWRITE backup (only possible in free-text columns, e.g. a name that
 * is exactly backslash-N) restores as SQL NULL on nullable columns, and
 * aborts loudly on NOT NULL columns. New-format backups are immune because
 * literal "\N" is always force-quoted.
 */
public class Backup {

    /** NULL marker: unquoted \N in the CSV (MySQL LOAD DATA convention). */
    private static final String NULL_MARKER = "\\N";

    /** One parsed CSV field; quoted distinguishes quoted "\N" (data) from unquoted \N (NULL). */
    private record CsvCell(String value, boolean quoted) {
    }

    private static final CsvCell EMPTY_CELL = new CsvCell("", false);

    public static void exportCsv(String tableName, File file, String orderKey) throws SQLException, IOException {
        Map<String, Field<?>> fields = Show.show().columns().from(tableName).execute();
        if (fields.isEmpty()) {
            throw new SQLException("Cannot export " + tableName + ": table has no columns or does not exist.");
        }
        StringBuilder builder = new StringBuilder();
        builder.append(joinCsv(new ArrayList<>(fields.keySet()))).append("\n");
        List<String> types = new ArrayList<>();
        for (Field<?> field : fields.values()) {
            types.add(field.getUnifyTypeStr());
        }
        builder.append(joinCsv(types)).append("\n");
        Field<?>[] columns = fields.values().toArray(new Field[0]);
        List<Map<String, Field<?>>> rows = Select
                .select(columns)
                .from(tableName)
                .ascend(orderKey)
                .execute();
        for (Map<String, Field<?>> row : rows) {
            // Align values by header keys explicitly: both the header map and
            // the Select row maps are HashMaps, so iterating the row map would
            // only align by luck (same hashes, same capacity).
            List<String> values = new ArrayList<>();
            for (String key : fields.keySet()) {
                Object value = row.get(key).getValue();
                values.add(value == null ? null : String.valueOf(value));
            }
            builder.append(joinCsv(values)).append("\n");
        }
        Files.writeString(file.toPath(), builder.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Strictly parse a backup CSV into rows of fields. This is the single
     * source of strictness for the restore path: callers must validate with
     * the same method BEFORE wiping any table, so a corrupt file aborts the
     * restore before the database is touched.
     */
    public static List<Field<?>[]> parseTable(String tableName, File file) throws IOException, SQLException {
        List<List<CsvCell>> records = parseCsv(Files.readString(file.toPath()));
        if (records.size() < 2) {
            throw new IOException("Backup file " + file.getAbsolutePath() + " is corrupted: missing header rows.");
        }
        if (records.get(0).size() != records.get(1).size()) {
            throw new IOException("Backup file " + file.getAbsolutePath() + " is corrupted: column/type count mismatch.");
        }
        List<String> columns = records.get(0).stream().map(CsvCell::value).toList();
        List<String> types = records.get(1).stream().map(CsvCell::value).toList();

        // Schema drift tolerance: only import columns that exist in the current table.
        Map<String, Field<?>> existing = Show.show().columns().from(tableName).execute();
        List<Integer> importIdx = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (existing.containsKey(columns.get(i))) {
                importIdx.add(i);
            } else {
                skipped.add(columns.get(i));
            }
        }
        if (!skipped.isEmpty()) {
            XLogger.warn("Importing " + tableName + ": skipping unknown column(s) " + skipped);
        }

        List<Field<?>[]> out = new ArrayList<>();
        for (int i = 2; i < records.size(); i++) {
            List<CsvCell> rec = records.get(i);
            if (rec.size() > columns.size()) {
                throw new IOException("Backup file " + file.getAbsolutePath() + " is corrupted: "
                        + "row " + (i - 1) + " has " + rec.size() + " fields, expected at most " + columns.size() + ".");
            }
            Field<?>[] fields = new Field[importIdx.size()];
            for (int k = 0; k < importIdx.size(); k++) {
                int j = importIdx.get(k);
                CsvCell cell = j < rec.size() ? rec.get(j) : EMPTY_CELL;
                try {
                    fields[k] = buildField(columns.get(j), types.get(j), cell);
                } catch (Exception e) {
                    throw new SQLException("Failed to parse value of column '" + columns.get(j)
                            + "' at row " + (i - 1) + " of " + file.getName() + ": '" + cell.value() + "'", e);
                }
            }
            out.add(fields);
        }
        return out;
    }

    /**
     * Insert parsed rows on the given connection. The caller owns the
     * transaction (setAutoCommit(false), commit/rollback) so the whole
     * restore is atomic; Insert.execute() cannot be used because it checks
     * out a fresh pooled connection per row.
     */
    public static void insertRows(Connection conn, String tableName, List<Field<?>[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        XLogger.warn("Importing " + tableName + " (" + rows.size() + " rows)");
        // Reuse the ORM's INSERT builder (plain INSERT, no conflict clause).
        String sql = Insert.insert().into(tableName).values(rows.get(0)).getSql();
        int done = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Field<?>[] fields : rows) {
                for (int i = 0; i < fields.length; i++) {
                    ps.setObject(i + 1, fields[i].getValue());
                }
                ps.executeUpdate();
                done++;
                if (done % 500 == 0 || done == rows.size()) {
                    XLogger.warn("Importing " + tableName + " " + done + "/" + rows.size()
                            + "\t\tProgress: " + done * 100f / rows.size() + "%");
                }
            }
        } catch (SQLException e) {
            // FK violations etc. land here mid-restore: give ops the table
            // and row number to locate the offending record.
            throw new SQLException("Failed to insert row " + (done + 1) + " into "
                    + tableName + ": " + e.getMessage(), e);
        }
        XLogger.warn("Importing " + tableName + " finished");
    }

    public static List<Map<String, Field<?>>> parseCsvToRows(File file) throws IOException {
        List<List<CsvCell>> records = parseCsv(Files.readString(file.toPath()));
        List<Map<String, Field<?>>> rows = new ArrayList<>();
        if (records.size() < 3) return rows;
        List<String> columns = records.get(0).stream().map(CsvCell::value).toList();
        List<String> types = records.get(1).stream().map(CsvCell::value).toList();
        for (int i = 2; i < records.size(); i++) {
            List<CsvCell> rec = records.get(i);
            Map<String, Field<?>> row = new LinkedHashMap<>();
            for (int j = 0; j < columns.size(); j++) {
                CsvCell cell = j < rec.size() ? rec.get(j) : EMPTY_CELL;
                try {
                    row.put(columns.get(j), buildField(columns.get(j), types.get(j), cell));
                } catch (SQLException e) {
                    throw new IOException("Failed to parse row " + (i - 1) + " of " + file.getName(), e);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static Field<?> buildField(String column, String type, CsvCell cell) throws SQLException {
        if (!cell.quoted() && cell.value().equals(NULL_MARKER)) {
            return nullField(column, type);
        }
        String value = cell.value();
        if (type.equals(new FieldBoolean("").getUnifyTypeStr())) {
            return new FieldBoolean(column, Boolean.parseBoolean(value));
        } else if (type.equals(new FieldFloat("").getUnifyTypeStr())) {
            return new FieldFloat(column, Float.parseFloat(value));
        } else if (type.equals(new FieldInteger("").getUnifyTypeStr())) {
            return new FieldInteger(column, Integer.parseInt(value));
        } else if (type.equals(new FieldLong("").getUnifyTypeStr())) {
            return new FieldLong(column, Long.parseLong(value));
        } else if (type.equals(new FieldString("").getUnifyTypeStr())) {
            return new FieldString(column, value);
        } else if (type.equals(new FieldTimestamp("").getUnifyTypeStr())) {
            return new FieldTimestamp(column, java.sql.Timestamp.valueOf(value));
        } else {
            throw new SQLException("Unsupported type: " + type + " for importing");
        }
    }

    private static Field<?> nullField(String column, String type) throws SQLException {
        if (type.equals(new FieldBoolean("").getUnifyTypeStr())) {
            return new FieldBoolean(column, null);
        } else if (type.equals(new FieldFloat("").getUnifyTypeStr())) {
            return new FieldFloat(column, null);
        } else if (type.equals(new FieldInteger("").getUnifyTypeStr())) {
            return new FieldInteger(column, null);
        } else if (type.equals(new FieldLong("").getUnifyTypeStr())) {
            return new FieldLong(column, null);
        } else if (type.equals(new FieldString("").getUnifyTypeStr())) {
            return new FieldString(column, null);
        } else if (type.equals(new FieldTimestamp("").getUnifyTypeStr())) {
            return new FieldTimestamp(column, null);
        } else {
            throw new SQLException("Unsupported type: " + type + " for importing");
        }
    }

    private static String joinCsv(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            String value = fields.get(i);
            sb.append(value == null ? NULL_MARKER : escapeCsv(value));
        }
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        boolean padded = !value.isEmpty()
                && (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1)));
        if (padded || value.equals(NULL_MARKER)
                || value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Whole-content CSV state machine. Handles quoted fields containing
     * commas, double-quote escapes, CR/LF and embedded newlines.
     *
     * A quote only OPENS a quoted field at the very start of a field:
     * pre-rewrite backups wrote values containing raw mid-field quotes
     * (e.g. Say "hi") unquoted, and those must parse back verbatim.
     * Quoted content is preserved verbatim; unquoted fields are trimmed
     * for leniency with hand-edited files.
     */
    private static List<List<CsvCell>> parseCsv(String content) {
        List<List<CsvCell>> records = new ArrayList<>();
        List<CsvCell> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldQuoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                inQuotes = true;
                fieldQuoted = true;
            } else if (c == ',') {
                record.add(new CsvCell(fieldQuoted ? field.toString() : field.toString().trim(), fieldQuoted));
                field.setLength(0);
                fieldQuoted = false;
            } else if (c == '\n') {
                record.add(new CsvCell(fieldQuoted ? field.toString() : field.toString().trim(), fieldQuoted));
                field.setLength(0);
                fieldQuoted = false;
                records.add(record);
                record = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (inQuotes) {
            // EOF inside a quoted field: old-format backups could start a
            // value with a raw quote and never close it; without this check
            // the rest of the file would silently collapse into one cell
            // and parse into garbage rows on free-text tables.
            throw new IllegalArgumentException("Unbalanced quote in CSV content: EOF reached inside a quoted field.");
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(new CsvCell(fieldQuoted ? field.toString() : field.toString().trim(), fieldQuoted));
            records.add(record);
        }
        records.removeIf(r -> r.size() == 1 && r.get(0).value().isEmpty() && !r.get(0).quoted());
        return records;
    }
}
