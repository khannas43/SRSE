package gov.rajasthan.smart.srse.lakehouse;

import java.util.regex.Pattern;

/**
 * Defence-in-depth guard for the one class of value that CANNOT be a bound
 * parameter: a SQL identifier.
 *
 * <p>Catalog and schema names are interpolated into SQL text
 * ({@code <catalog>.information_schema.tables}) because Presto has no
 * placeholder form for a catalog qualifier — {@code ? .information_schema}
 * is not valid SQL. The PRIMARY safety mechanism is still the allow-list:
 * {@link LakehouseBrowseService} validates every catalog against
 * {@code SHOW CATALOGS}, every schema against that catalog's
 * {@code information_schema.schemata}, and so on down the hierarchy, so only
 * names the lakehouse itself reported can ever reach SQL text.
 *
 * <p>This class is the belt to that pair of braces: even a name echoed back
 * by the lakehouse must look like a plain Presto identifier before it is
 * concatenated. It exists so that a compromised or misbehaving upstream
 * catalog cannot smuggle SQL through the allow-list, and so that the
 * validation query itself (which must interpolate the catalog to run at all)
 * is safe on the very first, not-yet-validated use.
 */
public final class LakehouseIdentifiers {

    /**
     * Unquoted Presto identifier: letter or underscore, then letters, digits
     * or underscores. Deliberately stricter than Presto's full grammar — it
     * rejects quoted identifiers containing dots, spaces or quotes rather
     * than trying to escape them, because the Golden/Silver layer naming
     * convention (e.g. {@code iceberg_data}, {@code jan_aadhar_data_txn},
     * {@code tbl_txn_bankdtl}) never needs them. A lakehouse object that
     * genuinely requires a quoted identifier is rejected loudly here rather
     * than silently mis-quoted downstream.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final int MAX_LENGTH = 128;

    private LakehouseIdentifiers() {
    }

    /**
     * @return {@code name} unchanged if it is a safe bare identifier.
     * @throws IllegalArgumentException otherwise — never returns a sanitised
     *         or truncated variant, because silently rewriting an identifier
     *         would point a query at a different object than the caller asked for.
     */
    public static String requireSafe(String kind, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(kind + " is required");
        }
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(kind + " exceeds " + MAX_LENGTH + " characters");
        }
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Illegal " + kind + " identifier: " + name
                            + " (expected letters, digits and underscores only)");
        }
        return name;
    }

    /** True if {@code name} is a safe bare identifier — the non-throwing form. */
    public static boolean isSafe(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_LENGTH
                && IDENTIFIER.matcher(name).matches();
    }
}
