package gov.rajasthan.smart.srse.analysis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only lakehouse schema introspection for the Analysis tab.
 *
 * Deliberately separate from the Rule Engine's {@code FieldResolver}/field
 * catalogue: this tab lets an officer pick ANY table/column ad hoc — a
 * confirmed, explicitly-scoped exception to CLAUDE.md's flat-catalogue /
 * no-JOIN rule, kept isolated to this one data-quality/reconciliation tool.
 * The "allow-list" here is the live lakehouse schema itself, re-fetched on
 * every call (not a pre-registered set of fields) — {@link RecordMatchService}
 * validates every table/column in a match request against it before that
 * identifier ever reaches SQL text.
 */
@Service
public class AnalysisSchemaService {

    private final JdbcTemplate jdbc;

    public AnalysisSchemaService(@Qualifier("prestoJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> listTables() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, currentSchema());
    }

    public List<ColumnInfo> listColumns(String table) {
        validateTable(table);
        return jdbc.query(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                (rs, rowNum) -> new ColumnInfo(rs.getString("column_name"), rs.getString("data_type")),
                currentSchema(), table);
    }

    /** Throws if {@code table} is not a real table in the live schema — the allow-list check. */
    public void validateTable(String table) {
        if (!listTables().contains(table)) {
            throw new IllegalArgumentException("Unknown table: " + table);
        }
    }

    /** Throws if {@code column} is not a real column of {@code table} in the live schema. */
    public void validateColumn(String table, String column) {
        validateTable(table);
        boolean known = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                String.class, currentSchema(), table, column)
                .stream().anyMatch(column::equals);
        if (!known) {
            throw new IllegalArgumentException("Unknown column: " + table + "." + column);
        }
    }

    /**
     * Reads the connection's active schema rather than a hardcoded config
     * value, so this always matches whatever the admin's live Presto
     * connection currently points at (see {@code SwappableDataSource}) even
     * after a live connection-string edit.
     */
    private String currentSchema() {
        return jdbc.execute((ConnectionCallback<String>) conn -> conn.getSchema());
    }

    public record ColumnInfo(String name, String dataType) {
    }
}
