package gov.rajasthan.smart.srse.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnGroupSqlTest {

    /** A group that folds nothing must not wrap anything — that is what keeps 1:1 SQL unchanged. */
    @Test
    void singleColumnIsEmittedBare() {
        assertEquals("tgt.full_name", ColumnGroupSql.combine(List.of("tgt.full_name"), " "));
    }

    @Test
    void combineCastsEveryMemberAndFiltersEmptyOnes() {
        String sql = ColumnGroupSql.combine(List.of("tgt.first_name", "tgt.last_name"), " ");

        assertEquals("array_join(filter(ARRAY[CAST(tgt.first_name AS VARCHAR), "
                + "CAST(tgt.last_name AS VARCHAR)], x -> x IS NOT NULL AND x <> ''), ' ')", sql);
    }

    /**
     * Not concat_ws. A NULL middle name would leave a doubled separator there,
     * which Levenshtein charges as an edit against every row that has one — so
     * the records with a missing middle name would be the ones that stop
     * matching.
     */
    @Test
    void combineNeverUsesConcatWs() {
        String sql = ColumnGroupSql.combine(List.of("a", "b", "c"), " ");

        assertFalse(sql.contains("concat_ws"), sql);
        assertTrue(sql.contains("filter("), sql);
    }

    @Test
    void combineEscapesAQuoteInTheSeparator() {
        String sql = ColumnGroupSql.combine(List.of("a", "b"), "'");

        assertTrue(sql.endsWith("), '''')"), sql);
    }

    @Test
    void unnestPairsEachValueWithItsColumnName() {
        String sql = ColumnGroupSql.unnestClause(
                "t", List.of("ja_id", "legacy_id"), false, "u0", "g0_key", "g0_matched_on");

        assertEquals("CROSS JOIN UNNEST(ARRAY[t.ja_id, t.legacy_id], "
                + "ARRAY['ja_id', 'legacy_id']) AS u0 (g0_key, g0_matched_on)", sql);
    }

    /** One element type per ARRAY — needed only when the columns disagree on family. */
    @Test
    void unnestCastsMembersOnlyWhenAsked() {
        String sql = ColumnGroupSql.unnestClause(
                "t", List.of("ja_id", "legacy_id"), true, "u0", "g0_key", "g0_matched_on");

        assertTrue(sql.contains("ARRAY[CAST(t.ja_id AS VARCHAR), CAST(t.legacy_id AS VARCHAR)]"), sql);
    }

    /** The whole reason ANY_OF is pivoted rather than written as alternatives. */
    @Test
    void unnestNeverProducesAnOr() {
        String sql = ColumnGroupSql.unnestClause(
                "t", List.of("a", "b", "c"), false, "u1", "g1_key", "g1_matched_on");

        assertFalse(sql.contains(" OR "), sql);
    }
}
