package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

import java.util.List;

/**
 * One target table in a {@link MultiTargetRecordMatchRequest}. Join criteria
 * pair 1:1 with the hub's join criteria; display columns are projected only.
 *
 * <p>{@code joinGroups}, when present, replaces that positional pairing for
 * this target — the hub's {@code full_name} against this target's
 * {@code first_name} + {@code last_name}, say. Each target carries its own
 * groups, because each target's columns are its own.
 */
public record TargetMatchSpec(
        String label,
        String catalog,
        String schema,
        String table,
        List<MatchCriterion> joinCriteria,
        List<DisplayColumn> displayColumns,
        List<MatchGroup> joinGroups,
        JoinType joinType,
        List<ComparisonGroup> comparisonGroups) {

    public TargetMatchSpec {
        joinCriteria = joinCriteria == null ? List.of() : List.copyOf(joinCriteria);
        displayColumns = displayColumns == null ? List.of() : displayColumns;
        joinGroups = joinGroups == null ? List.of() : List.copyOf(joinGroups);
        comparisonGroups = comparisonGroups == null ? List.of() : List.copyOf(comparisonGroups);
    }

    /**
     * The target-side columns this spec actually joins on — flattened out of
     * the groups when it has them, so the merged layout projects the same
     * columns the ON clause used.
     */
    public List<MatchCriterion> effectiveJoinColumns() {
        if (joinGroups.isEmpty()) {
            return joinCriteria;
        }
        return joinGroups.stream().flatMap(g -> g.target().stream()).toList();
    }

    /** {@code matched_on} aliases this target's ANY_OF groups will project. */
    public List<String> matchedOnColumns() {
        List<String> aliases = new java.util.ArrayList<>();
        for (int i = 0; i < joinGroups.size(); i++) {
            if (joinGroups.get(i).mode() == GroupMode.ANY_OF && joinGroups.get(i).target().size() > 1) {
                aliases.add("g" + i + "_matched_on");
            }
        }
        return aliases;
    }

    /** The pre-groups shape: join criteria paired positionally with the hub's. */
    public TargetMatchSpec(String label, String catalog, String schema, String table,
                           List<MatchCriterion> joinCriteria, List<DisplayColumn> displayColumns) {
        this(label, catalog, schema, table, joinCriteria, displayColumns, List.of(), null, List.of());
    }

    public TargetMatchSpec(String label, String catalog, String schema, String table,
                           List<MatchCriterion> joinCriteria, List<DisplayColumn> displayColumns,
                           List<MatchGroup> joinGroups) {
        this(label, catalog, schema, table, joinCriteria, displayColumns, joinGroups, null, List.of());
    }

    public TargetMatchSpec(String label, String catalog, String schema, String table,
                           List<MatchCriterion> joinCriteria, List<DisplayColumn> displayColumns,
                           List<MatchGroup> joinGroups, JoinType joinType) {
        this(label, catalog, schema, table, joinCriteria, displayColumns, joinGroups, joinType, List.of());
    }

    public QualifiedTable qualifiedTable() {
        return new QualifiedTable(catalog, schema, table);
    }

    public String qualifiedTableName() {
        return qualifiedTable().qualifiedName();
    }
}
