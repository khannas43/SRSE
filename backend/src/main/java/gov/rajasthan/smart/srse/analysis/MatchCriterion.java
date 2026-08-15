package gov.rajasthan.smart.srse.analysis;

/**
 * One Source or Target (table, column) pick from the Analysis tab's dropdowns.
 * {@link RecordMatchService} requires every criterion on the same side (all
 * Source, or all Target) to share the same {@code table} — a composite match
 * key of 1-N columns from one table, not an N-way join across tables — to
 * keep the underlying join a simple two-table comparison.
 *
 * {@code fuzzyThresholdPercent} is set on the Source-side criterion only
 * (0..100) and applies to that (source, target) column pair when either
 * column name contains "name"; ignored/null otherwise and on Target entries.
 */
public record MatchCriterion(String table, String column, Double fuzzyThresholdPercent) {
}
