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
}
