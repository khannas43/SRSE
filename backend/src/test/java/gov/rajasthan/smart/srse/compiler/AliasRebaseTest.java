package gov.rajasthan.smart.srse.compiler;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AliasRebaseTest {

    @Test
    void rebasesAPlainTableQualifiedColumn() {
        assertEquals("src.age_years", AliasRebase.ontoAlias("beneficiary.age_years", "src"));
    }

    @Test
    void rebasesAFullyQualifiedFourPartColumn() {
        assertEquals("tgt.age_years",
                AliasRebase.ontoAlias("iceberg_gold.golden_layer.tbl_beneficiary.age_years", "tgt"));
    }

    /**
     * The bug this class exists for. Taking everything after the last dot
     * yielded "date_of_birth, current_date)", which the Analysis tab then
     * emitted as "src.date_of_birth, current_date) BETWEEN ? AND ?" — SQL
     * that does not parse.
     */
    @Test
    void rebasesTheDobDerivedAgeExpressionInsteadOfTruncatingIt() {
        assertEquals("date_diff('year', src.date_of_birth, current_date)",
                AliasRebase.ontoAlias(
                        "date_diff('year', beneficiary.date_of_birth, current_date)", "src"));
    }

    @Test
    void rebasesAFullyQualifiedDobExpression() {
        assertEquals("date_diff('year', tgt.date_of_birth, current_date)",
                AliasRebase.ontoAlias(
                        "date_diff('year', iceberg_gold.golden_layer.tbl_beneficiary.date_of_birth,"
                                + " current_date)", "tgt"));
    }

    /** Function names and keywords are bare identifiers and must survive untouched. */
    @Test
    void leavesBareIdentifiersAlone() {
        assertEquals("current_date", AliasRebase.ontoAlias("current_date", "src"));
        assertEquals("coalesce(src.a, 0)", AliasRebase.ontoAlias("coalesce(t.a, 0)", "src"));
    }

    /** A dot inside a string literal is not qualification. */
    @Test
    void doesNotRewriteInsideQuotedLiterals() {
        assertEquals("concat('a.b', src.c)", AliasRebase.ontoAlias("concat('a.b', t.c)", "src"));
    }

    @Test
    void handlesDoubledQuoteEscapeInsideALiteral() {
        assertEquals("concat('it''s a.b', src.c)",
                AliasRebase.ontoAlias("concat('it''s a.b', t.c)", "src"));
    }

    /** Identifiers cannot start with a digit, so a decimal literal is never a chain. */
    @Test
    void leavesNumericLiteralsAlone() {
        assertEquals("src.ratio * 1.5", AliasRebase.ontoAlias("beneficiary.ratio * 1.5", "src"));
    }

    @Test
    void rebasesEveryColumnReferenceInAMultiColumnExpression() {
        assertEquals("src.a + src.b",
                AliasRebase.ontoAlias("beneficiary.a + beneficiary.b", "src"));
    }

    /** Unterminated literal is copied through rather than swallowed — the DB reports it. */
    @Test
    void copiesAnUnterminatedLiteralVerbatim() {
        assertEquals("concat('oops", AliasRebase.ontoAlias("concat('oops", "src"));
    }

    // ---- referencedColumns: what ontoAlias would rebase ----

    /**
     * The age filter asks this before emitting, to find out whether a table can
     * carry the expression at all — so what comes back must be exactly what
     * ontoAlias would rewrite, no more.
     */
    @Test
    void referencedColumnsFindsTheColumnOfAPlainQualifiedBinding() {
        assertEquals(Set.of("age_years"),
                AliasRebase.referencedColumns("iceberg_gold.golden.tbl_ben.age_years"));
    }

    @Test
    void referencedColumnsFindsTheDobInsideATier2Expression() {
        assertEquals(Set.of("date_of_birth"), AliasRebase.referencedColumns(
                "date_diff('year', CAST(golden.gold.citizen_360.date_of_birth AS DATE), current_date)"));
    }

    /**
     * Bare identifiers are function names and keywords, not columns — requiring
     * a table to have a column called "current_date" would disable the filter
     * everywhere.
     */
    @Test
    void referencedColumnsIgnoresBareIdentifiersAndQuotedLiterals() {
        Set<String> columns = AliasRebase.referencedColumns(
                "date_diff('year', beneficiary.date_of_birth, current_date)");

        assertEquals(Set.of("date_of_birth"), columns);
    }

    @Test
    void referencedColumnsIsEmptyForAnExpressionWithNoQualifiedReference() {
        assertEquals(Set.of(), AliasRebase.referencedColumns("current_date"));
    }

}
