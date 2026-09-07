package gov.rajasthan.smart.srse.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The FROM clause of every rule query is derived from one field's binding, so
 * this is what decides whether two fields are "the same table" — and a wrong
 * answer surfaces as Presto's "User defined type is not supported", which names
 * neither the table nor the field.
 */
class FieldColumnMappingTableOfTest {

    @Test
    void takesTheTableOffAFullyQualifiedBinding() {
        assertEquals("iceberg_gold.golden.tbl_ben",
                FieldColumnMapping.tableOf("iceberg_gold.golden.tbl_ben.age_years"));
    }

    @Test
    void takesTheTableOffABareBinding() {
        assertEquals("beneficiary", FieldColumnMapping.tableOf("beneficiary.age_years"));
    }

    /**
     * The two forms of the same physical table are deliberately NOT equal here.
     * Mixing them is exactly the misconfiguration this exists to catch: the
     * FROM says one, the WHERE says the other, and Presto rejects the query.
     */
    @Test
    void bareAndQualifiedFormsAreDifferentTables() {
        assertEquals("beneficiary", FieldColumnMapping.tableOf("beneficiary.district"));
        assertEquals("iceberg.srse.beneficiary",
                FieldColumnMapping.tableOf("iceberg.srse.beneficiary.district"));
    }

    /**
     * A Tier-2 expression has no single table to take, and its last dot is
     * INSIDE the expression — taking everything before it would yield garbage
     * and, worse, a garbage table that compares unequal to every other.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "date_diff('year', beneficiary.date_of_birth, current_date)",
            "CAST(tbl.dob AS DATE)",
            "TRY_CAST(a.b.c AS DOUBLE)",
    })
    void expressionsHaveNoTable(String expression) {
        assertNull(FieldColumnMapping.tableOf(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"age_years", ".age_years", ""})
    void unqualifiedOrMalformedBindingsHaveNoTable(String expression) {
        assertNull(FieldColumnMapping.tableOf(expression));
    }

    @Test
    void nullIsNullRatherThanAnException() {
        assertNull(FieldColumnMapping.tableOf(null));
    }
}
