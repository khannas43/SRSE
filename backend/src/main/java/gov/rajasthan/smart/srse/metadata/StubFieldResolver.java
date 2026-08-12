package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * TEMPORARY stub resolver so the skeleton compiles and runs before the
 * metadata mapping service exists. Active whenever DATA_MODE=synthetic
 * (the default), regardless of Spring profile — mirrors {@link MetadataFieldResolver}'s
 * gate on the same property so exactly one resolver is ever active.
 *
 * BUILD TASK (design doc §6.4): replace with a metadata-backed resolver that
 * reads field_mapping (DB2/JPA) and resolves per-environment bindings
 * (Tier 1 column / Tier 2 expression), cached via Caffeine.
 */
@Component
@ConditionalOnProperty(name = "srse.data-mode", havingValue = "synthetic", matchIfMissing = true)
public class StubFieldResolver implements FieldResolver {

    private static final Map<String, String> MAP = Map.ofEntries(
            Map.entry("age_years", "beneficiary.age_years"),
            Map.entry("gender", "beneficiary.gender"),
            Map.entry("district", "beneficiary.district"),
            Map.entry("annual_income_total", "beneficiary.annual_income_total"),
            Map.entry("marital_status", "beneficiary.marital_status"),
            Map.entry("is_domicile_holder", "beneficiary.is_domicile_holder"),
            Map.entry("ration_card_category", "beneficiary.ration_card_category"),
            Map.entry("census_category", "beneficiary.census_category"),
            Map.entry("community", "beneficiary.community"),
            Map.entry("disability_pct", "beneficiary.disability_pct"),
            Map.entry("tsp_classification", "beneficiary.tsp_classification"),
            Map.entry("class_passed", "beneficiary.class_passed"),
            Map.entry("is_girl_child_of_hof", "beneficiary.is_girl_child_of_hof"),
            Map.entry("has_vehicle", "beneficiary.has_vehicle"),
            Map.entry("land_holding_sqyd", "beneficiary.land_holding_sqyd"),
            Map.entry("relationship_to_hof", "beneficiary.relationship_to_hof"),
            Map.entry("father_name", "beneficiary.father_name"),
            Map.entry("mother_name", "beneficiary.mother_name"),
            Map.entry("annual_income_fy2627", "beneficiary.annual_income_fy2627"),
            Map.entry("annual_income_fy2526", "beneficiary.annual_income_fy2526"),
            Map.entry("annual_income_fy2425", "beneficiary.annual_income_fy2425"),
            Map.entry("annual_income_fy2324", "beneficiary.annual_income_fy2324"),
            Map.entry("annual_income_fy2223", "beneficiary.annual_income_fy2223"),
            Map.entry("annual_income_fy2122", "beneficiary.annual_income_fy2122"),
            Map.entry("annual_income_fy2021", "beneficiary.annual_income_fy2021"),
            Map.entry("annual_income_fy1920", "beneficiary.annual_income_fy1920"),
            Map.entry("annual_income_fy1819", "beneficiary.annual_income_fy1819"),
            Map.entry("annual_income_fy1718", "beneficiary.annual_income_fy1718")
    );

    @Override
    public String resolveColumn(String fieldKey) {
        String col = MAP.get(fieldKey);
        if (col == null) throw new UnknownFieldException(fieldKey);
        return col;
    }
}
