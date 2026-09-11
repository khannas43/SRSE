package gov.rajasthan.smart.srse.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgeYearsExpressionTest {

    @Test
    void leavesPlainNumericAgeColumnUntouched() {
        assertEquals("beneficiary.age_years",
                AgeYearsExpression.normalize("age_years", "beneficiary.age_years"));
        assertEquals("iceberg_gold.golden_layer.tbl_beneficiary.age_years",
                AgeYearsExpression.normalize("age_years",
                        "iceberg_gold.golden_layer.tbl_beneficiary.age_years"));
    }

    @Test
    void promotesPlainDobColumnToDateDiffWithCast() {
        assertEquals(
                "date_diff('year', CAST(golden_data.gold_metadata.citizen_360.date_of_birth AS DATE), current_date)",
                AgeYearsExpression.normalize("age_years",
                        "golden_data.gold_metadata.citizen_360.date_of_birth"));
    }

    @Test
    void doesNotPromoteDateOfMarriageColumn() {
        assertEquals("golden_data.gold_metadata.citizen_360.date_of_marriage",
                AgeYearsExpression.normalize("age_years",
                        "golden_data.gold_metadata.citizen_360.date_of_marriage"));
    }

    @Test
    void addsCastInsideExistingDateDiffExpression() {
        assertEquals(
                "date_diff('year', CAST(golden_data.gold_metadata.citizen_360.date_of_birth AS DATE), current_date)",
                AgeYearsExpression.normalize("age_years",
                        "date_diff('year', golden_data.gold_metadata.citizen_360.date_of_birth, current_date)"));
    }

    @Test
    void leavesDateDiffThatAlreadyHasCast() {
        String expr = "date_diff('year', CAST(golden_data.gold_metadata.citizen_360.date_of_birth AS DATE),"
                + " current_date)";
        assertEquals(expr, AgeYearsExpression.normalize("age_years", expr));
    }

    @Test
    void ignoresOtherFieldKeys() {
        assertEquals("beneficiary.date_of_birth",
                AgeYearsExpression.normalize("date_of_birth", "beneficiary.date_of_birth"));
    }

    @Test
    void marriageColumnIsNotTreatedAsDob() {
        assertFalse(AgeYearsExpression.looksLikeDobColumn("date_of_marriage"));
    }
}
