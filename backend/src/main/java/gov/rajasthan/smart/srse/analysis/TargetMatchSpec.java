package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;

import java.util.List;

/**
 * One target table in a {@link MultiTargetRecordMatchRequest}. Join criteria
 * pair 1:1 with the hub's join criteria; display columns are projected only.
 */
public record TargetMatchSpec(
        String label,
        String catalog,
        String schema,
        String table,
        List<MatchCriterion> joinCriteria,
        List<DisplayColumn> displayColumns) {

    public TargetMatchSpec {
        displayColumns = displayColumns == null ? List.of() : displayColumns;
    }

    public QualifiedTable qualifiedTable() {
        return new QualifiedTable(catalog, schema, table);
    }

    public String qualifiedTableName() {
        return qualifiedTable().qualifiedName();
    }
}
