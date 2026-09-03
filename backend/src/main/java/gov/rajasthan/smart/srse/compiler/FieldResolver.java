package gov.rajasthan.smart.srse.compiler;

/**
 * Resolves an abstract catalogue field key to its physical Presto SQL reference
 * (a column name or a same-table expression). Backed by the metadata mapping
 * service. This is an ALLOW-LIST: only known field keys resolve; unknown keys
 * throw. No user-supplied string ever reaches SQL as an identifier.
 */
public interface FieldResolver {

    /**
     * @param fieldKey abstract catalogue key (e.g. "age_years")
     * @return physical SQL reference (e.g. "beneficiary.age_years" or
     *         "date_diff('year', dob, current_date)")
     * @throws UnknownFieldException if the key is not in the catalogue/mapping
     */
    String resolveColumn(String fieldKey);

    class UnknownFieldException extends RuntimeException {
        public UnknownFieldException(String fieldKey) {
            super("Unknown or unmapped field key: " + fieldKey);
        }
    }

    /**
     * The field IS catalogued and IS mapped — but its mapping is still the
     * shipped {@code CHANGE_ME} placeholder, i.e. nobody has bound it to a real
     * Golden Layer column for this environment yet.
     *
     * <p>Distinct from {@link UnknownFieldException} because the remedy is
     * different and the old behaviour was actively misleading: the placeholder
     * used to flow through into SQL, so an unconfigured deployment asked Presto
     * for a table literally named {@code CHANGE_ME}. Presto then resolved that
     * against the connection's default catalog/schema and reported
     * "Table silver_data.jan_aadhaar_txn.change_me does not exist" — naming a
     * table nobody had configured anywhere, which sent readers looking for a
     * phantom registration instead of at the unmapped field.
     */
    class UnconfiguredFieldException extends RuntimeException {
        public UnconfiguredFieldException(String fieldKey, String physicalExpression) {
            super("Field '" + fieldKey + "' has no physical mapping for this environment yet — "
                    + "it is still the placeholder '" + physicalExpression + "'. "
                    + "Set it on the Admin page under \"Field -> catalog/schema/table/column mappings\" "
                    + "(use \"Pick from lakehouse...\"), or replace the CHANGE_ME entries in "
                    + "field-catalog-seed.yml, before running a simulation.");
        }
    }
}
