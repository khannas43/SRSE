package gov.rajasthan.smart.srse.lakehouse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakehouseIdentifiersTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "iceberg_data", "jan_aadhar_data_txn", "tbl_txn_bankdtl",
            "bank_id", "m_id", "account_no", "bank_branch_id",
            "_leading_underscore", "Mixed_Case_9"})
    void acceptsRealLakehouseNames(String name) {
        assertTrue(LakehouseIdentifiers.isSafe(name));
        assertEquals(name, LakehouseIdentifiers.requireSafe("catalog", name));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a.b",                      // would silently re-qualify the reference
            "a b",
            "a\"b",
            "a'b",
            "a;DROP TABLE x",
            "a--comment",
            "1_leading_digit",
            "a UNION SELECT 1"})
    void rejectsAnythingThatIsNotABareIdentifier(String name) {
        assertFalse(LakehouseIdentifiers.isSafe(name));
        assertThrows(IllegalArgumentException.class,
                () -> LakehouseIdentifiers.requireSafe("schema", name));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsBlank(String name) {
        assertFalse(LakehouseIdentifiers.isSafe(name));
        assertThrows(IllegalArgumentException.class,
                () -> LakehouseIdentifiers.requireSafe("table", name));
    }

    @Test
    void rejectsOverlyLongIdentifier() {
        String tooLong = "a".repeat(129);
        assertFalse(LakehouseIdentifiers.isSafe(tooLong));
        assertThrows(IllegalArgumentException.class,
                () -> LakehouseIdentifiers.requireSafe("column", tooLong));
    }

    /** Never sanitises — a rewritten identifier would point at a different object. */
    @Test
    void errorMessageNamesTheKindAndTheOffendingValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LakehouseIdentifiers.requireSafe("catalog", "bad name"));
        assertTrue(e.getMessage().contains("catalog"), e.getMessage());
        assertTrue(e.getMessage().contains("bad name"), e.getMessage());
    }
}
