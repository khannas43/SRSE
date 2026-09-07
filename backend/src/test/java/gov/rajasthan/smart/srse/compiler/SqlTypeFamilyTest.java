package gov.rajasthan.smart.srse.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The type strings here are the ones {@code information_schema.columns} really
 * reports, parameters and all — a family decided off {@code "varchar(50)"} by
 * naive string equality would fall straight through to UNKNOWN and quietly
 * disable every coercion.
 */
class SqlTypeFamilyTest {

    @ParameterizedTest
    @ValueSource(strings = {"varchar", "varchar(50)", "VARCHAR(255)", "char(3)", "character varying"})
    void textTypes(String dataType) {
        assertEquals(SqlTypeFamily.TEXT, SqlTypeFamily.of(dataType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bigint", "integer", "int", "smallint", "tinyint",
            "double", "double precision", "real", "decimal(10,2)", "DECIMAL(38,0)"})
    void numericTypes(String dataType) {
        assertEquals(SqlTypeFamily.NUMBER, SqlTypeFamily.of(dataType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "timestamp", "timestamp(3)", "timestamp(3) with time zone", "time"})
    void temporalTypes(String dataType) {
        assertEquals(SqlTypeFamily.TEMPORAL, SqlTypeFamily.of(dataType));
    }

    @Test
    void booleanType() {
        assertEquals(SqlTypeFamily.BOOLEAN, SqlTypeFamily.of("boolean"));
    }

    /** Anything SRSE cannot reason about must degrade to "leave it alone". */
    @ParameterizedTest
    @ValueSource(strings = {"varbinary", "json", "uuid", "array(varchar)", "map(varchar, varchar)",
            "row(a bigint)", "ipaddress", ""})
    void everythingElseIsUnknown(String dataType) {
        assertEquals(SqlTypeFamily.UNKNOWN, SqlTypeFamily.of(dataType));
    }

    @Test
    void nullIsUnknownRatherThanAnException() {
        assertEquals(SqlTypeFamily.UNKNOWN, SqlTypeFamily.of(null));
    }
}
