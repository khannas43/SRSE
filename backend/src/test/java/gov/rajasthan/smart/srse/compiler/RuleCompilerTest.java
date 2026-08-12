package gov.rajasthan.smart.srse.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler tests anchored to the design-doc Appendix B worked example:
 * Ekal Naari (Divorced Woman) pension — real thresholds.
 *
 * A trivial FieldResolver maps abstract keys to physical columns 1:1 so we can
 * assert the emitted SQL shape and parameter order without a live catalogue.
 */
class RuleCompilerTest {

    /** Identity-ish resolver: fieldKey -> "beneficiary.<fieldKey>". */
    private final FieldResolver resolver = fieldKey -> {
        // allow-list: only known keys resolve
        return switch (fieldKey) {
            case "marital_status", "gender", "age_years", "is_domicile_holder",
                 "annual_income_total", "ration_card_category", "community",
                 "father_name", "mother_name"
                    -> "beneficiary." + fieldKey;
            default -> throw new FieldResolver.UnknownFieldException(fieldKey);
        };
    };

    private final RuleCompiler compiler = new RuleCompiler(resolver);

    @Test
    void compilesEkalNaariOfficialRuleset() {
        // AND( marital=DIVORCED, gender=FEMALE, age>=18, domicile=TRUE,
        //      OR( income<48000, ration IN [BPL,ANTYODAYA],
        //          community IN [SAHARIYA,KATHODI,KHAIRWA] ) )
        var income = new Ast.PredicateNode("annual_income_total", Ast.Operator.LT, 48000);
        var ration = new Ast.PredicateNode("ration_card_category", Ast.Operator.IN,
                List.of("BPL", "ANTYODAYA"));
        var community = new Ast.PredicateNode("community", Ast.Operator.IN,
                List.of("SAHARIYA", "KATHODI", "KHAIRWA"));
        var incomeOrExempt = new Ast.GroupNode(Ast.BoolOp.OR,
                List.of(income, ration, community));

        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("marital_status", Ast.Operator.EQ, "DIVORCED"),
                new Ast.PredicateNode("gender", Ast.Operator.EQ, "FEMALE"),
                new Ast.PredicateNode("age_years", Ast.Operator.GTE, 18),
                new Ast.PredicateNode("is_domicile_holder", Ast.Operator.IS_TRUE),
                incomeOrExempt
        ));

        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(root));

        // Structural assertions (exact spacing tolerant).
        assertTrue(q.predicateSql().contains("beneficiary.marital_status = ?"));
        assertTrue(q.predicateSql().contains("beneficiary.age_years >= ?"));
        assertTrue(q.predicateSql().contains("beneficiary.is_domicile_holder = TRUE"));
        assertTrue(q.predicateSql().contains("beneficiary.ration_card_category IN (?, ?)"));
        assertTrue(q.predicateSql().contains(" OR "));
        assertTrue(q.predicateSql().contains(" AND "));

        // Param order: DIVORCED, FEMALE, 18, 48000, BPL, ANTYODAYA,
        //              SAHARIYA, KATHODI, KHAIRWA  (domicile has no bound param)
        assertEquals(
                List.of("DIVORCED", "FEMALE", 18, 48000,
                        "BPL", "ANTYODAYA", "SAHARIYA", "KATHODI", "KHAIRWA"),
                q.params());
    }

    @Test
    void whatIfChangesOnlyTheBoundParameter() {
        // Raising the income ceiling to 60000 changes the param, not the SQL shape.
        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("annual_income_total", Ast.Operator.LT, 60000)));
        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(root));
        assertTrue(q.predicateSql().contains("beneficiary.annual_income_total < ?"));
        assertEquals(List.of(60000), q.params());
    }

    @Test
    void unknownFieldIsRejected() {
        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("secret_backdoor", Ast.Operator.EQ, "x")));
        assertThrows(FieldResolver.UnknownFieldException.class,
                () -> compiler.compile(new Ast.PredicateSpec(root)));
    }

    @Test
    void compilesFuzzyMatchWithNormalizedLevenshteinSimilarity() {
        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("father_name", Ast.Operator.FUZZY_MATCH, List.of("Ramesh", 70))));

        CompiledQuery q = compiler.compile(new Ast.PredicateSpec(root));

        assertTrue(q.predicateSql().contains("levenshtein_distance(lower(beneficiary.father_name), lower(?))"));
        assertTrue(q.predicateSql().contains("GREATEST(length(beneficiary.father_name), length(?), 1)"));
        assertTrue(q.predicateSql().contains(">= ?"));
        // name bound twice (appears twice in the expression), then threshold as a 0..1 fraction.
        assertEquals(List.of("Ramesh", "Ramesh", 0.7), q.params());
    }

    @Test
    void fuzzyMatchRejectsThresholdOutOfRange() {
        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("father_name", Ast.Operator.FUZZY_MATCH, List.of("Ramesh", 150))));
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(new Ast.PredicateSpec(root)));
    }

    @Test
    void fuzzyMatchRejectsBlankName() {
        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("father_name", Ast.Operator.FUZZY_MATCH, List.of("  ", 80))));
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(new Ast.PredicateSpec(root)));
    }

    @Test
    void fuzzyMatchRejectsWrongValueShape() {
        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("father_name", Ast.Operator.FUZZY_MATCH, List.of("Ramesh"))));
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(new Ast.PredicateSpec(root)));
    }
}
