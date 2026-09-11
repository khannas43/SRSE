package gov.rajasthan.smart.srse.compiler;

/**
 * Normalises the physical expression for the catalogue's {@code age_years}
 * field so age comparisons compile to valid Presto SQL against real Golden
 * Layer columns.
 *
 * <p>Client tables often store DOB as a user-defined or timestamp type that
 * {@code date_diff('year', …, current_date)} rejects unless the middle
 * argument is {@code CAST(... AS DATE)} — the failure mode reported as
 * "User defined type is not supported". Admins may also bind {@code age_years}
 * directly to a DOB column and expect BETWEEN 18 AND 100 to mean age-in-years,
 * not calendar-date ordering.
 *
 * <p>Plain numeric {@code age_years} columns (the synthetic seed binding) are
 * left untouched — only DOB-shaped column names are auto-promoted to a Tier-2
 * {@code date_diff} form.
 */
public final class AgeYearsExpression {

    private static final String FIELD_KEY = "age_years";

    private AgeYearsExpression() {
    }

    public static String normalize(String fieldKey, String physicalExpression) {
        if (!FIELD_KEY.equals(fieldKey) || physicalExpression == null || physicalExpression.isBlank()) {
            return physicalExpression;
        }
        String trimmed = physicalExpression.trim();
        if (trimmed.toLowerCase().startsWith("date_diff('year',")) {
            return ensureCastInsideDateDiff(trimmed);
        }
        String fromPlainDob = fromPlainDobColumn(trimmed);
        if (fromPlainDob != null) {
            return fromPlainDob;
        }
        return trimmed;
    }

    /**
     * {@code catalog.schema.table.date_of_birth} → {@code date_diff('year', CAST(... AS DATE), current_date)}.
     * Numeric {@code …age_years} bindings are not DOB columns and are returned unchanged via {@code null}.
     */
    static String fromPlainDobColumn(String trimmed) {
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == trimmed.length() - 1) {
            return null;
        }
        String column = trimmed.substring(lastDot + 1);
        if (!looksLikeDobColumn(column)) {
            return null;
        }
        return "date_diff('year', CAST(" + trimmed + " AS DATE), current_date)";
    }

    static String ensureCastInsideDateDiff(String trimmed) {
        int yearIdx = trimmed.toLowerCase().indexOf("'year'");
        if (yearIdx < 0) {
            return trimmed;
        }
        int firstComma = trimmed.indexOf(',', yearIdx);
        int lastComma = trimmed.toLowerCase().lastIndexOf(", current_date");
        if (firstComma < 0 || lastComma < 0 || lastComma <= firstComma) {
            return trimmed;
        }
        String middle = trimmed.substring(firstComma + 1, lastComma).trim();
        if (middle.toUpperCase().startsWith("CAST(")) {
            return trimmed;
        }
        return "date_diff('year', CAST(" + middle + " AS DATE), current_date)";
    }

    static boolean looksLikeDobColumn(String columnName) {
        String lower = columnName.toLowerCase();
        return lower.equals("date_of_birth")
                || lower.equals("dob")
                || lower.endsWith("_dob")
                || lower.contains("date_of_birth")
                || (lower.contains("birth") && !lower.contains("marriage"));
    }
}
