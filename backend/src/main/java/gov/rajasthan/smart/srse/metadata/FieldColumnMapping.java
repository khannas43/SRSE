package gov.rajasthan.smart.srse.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Per-environment physical binding of a catalogue field key.
 *
 * <p>CONTRACT: NEVER store a Tier-3 cross-table JOIN here — Tier 3 fields must
 * already be flattened upstream by Spark ETL before a row exists in this table
 * (per CLAUDE.md flat-catalogue contract). {@code physicalExpression} holds either
 * a bare column ref (e.g. {@code beneficiary.age_years}) for Tier 1, or a
 * same-table SQL expression for Tier 2 / pre-materialised Tier 3.
 */
@Entity
@Table(
        name = "field_column_mapping",
        uniqueConstraints = @UniqueConstraint(columnNames = {"field_key", "data_mode"})
)
public class FieldColumnMapping {

    /**
     * The token the checked-in seed uses for a LIVE mapping nobody has bound
     * yet (e.g. {@code CHANGE_ME.age_years}). Matched as a whole identifier and
     * case-insensitively, so a genuine column such as {@code change_me_flag}
     * is not mistaken for it — Presto lower-cases unquoted identifiers, so the
     * placeholder is just as likely to surface as {@code change_me}.
     */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("(?i)\\bCHANGE_ME\\b");

    /** True if {@code physicalExpression} is still an unconfigured placeholder. */
    public static boolean isPlaceholder(String physicalExpression) {
        return physicalExpression != null && PLACEHOLDER.matcher(physicalExpression).find();
    }

    /**
     * The table a plain {@code [catalog.schema.]table.column} binding selects
     * from, or null when the binding is an EXPRESSION rather than a column
     * reference (a Tier-2 {@code date_diff(...)} form has no single table to
     * take, and its last dot is inside the expression).
     *
     * <p>Exists because every field of one environment must resolve against
     * the SAME flat table — that is the flat-catalogue contract (CLAUDE.md),
     * and {@code ExecutionService} relies on it when it derives the FROM
     * clause from one field's binding. When the bindings disagree, the query
     * names a table in FROM that its WHERE columns do not belong to, and
     * Presto reports it as "User defined type is not supported": an error that
     * mentions neither the table nor the mapping and sends the reader looking
     * at column types instead of at the Admin page.
     */
    public static String tableOf(String physicalExpression) {
        if (physicalExpression == null) {
            return null;
        }
        String trimmed = physicalExpression.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // Anything that is not part of a dotted identifier chain — a
            // paren, quote, space, operator — makes this an expression.
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                return null;
            }
        }
        int lastDot = trimmed.lastIndexOf('.');
        return lastDot <= 0 ? null : trimmed.substring(0, lastDot);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_mode", nullable = false)
    private DataMode dataMode;

    @Column(nullable = false)
    private String physicalExpression;

    protected FieldColumnMapping() {
    }

    public FieldColumnMapping(Long id, String fieldKey, DataMode dataMode,
                              String physicalExpression) {
        this.id = id;
        this.fieldKey = fieldKey;
        this.dataMode = dataMode;
        this.physicalExpression = physicalExpression;
    }

    public Long getId() {
        return id;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public DataMode getDataMode() {
        return dataMode;
    }

    public String getPhysicalExpression() {
        return physicalExpression;
    }
}
