package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * TEMPORARY stub resolver so the skeleton compiles and runs before the
 * metadata mapping service exists. Active only under the 'local' profile.
 *
 * BUILD TASK (design doc §6.4): replace with a metadata-backed resolver that
 * reads field_mapping (DB2/JPA) and resolves per-environment bindings
 * (Tier 1 column / Tier 2 expression), cached via Caffeine.
 */
@Component
@Profile("local")
public class StubFieldResolver implements FieldResolver {

    private static final Map<String, String> MAP = Map.ofEntries(
            Map.entry("age_years", "beneficiary.age_years"),
            Map.entry("gender", "beneficiary.gender"),
            Map.entry("district", "beneficiary.district"),
            Map.entry("annual_income_total", "beneficiary.annual_income_total"),
            Map.entry("marital_status", "beneficiary.marital_status"),
            Map.entry("is_domicile_holder", "beneficiary.is_domicile_holder"),
            Map.entry("ration_card_category", "beneficiary.ration_card_category"),
            Map.entry("community", "beneficiary.community"),
            Map.entry("disability_pct", "beneficiary.disability_pct"),
            Map.entry("is_enrolled_in_school", "beneficiary.is_enrolled_in_school"),
            Map.entry("is_girl_child_of_hof", "beneficiary.is_girl_child_of_hof")
    );

    @Override
    public String resolveColumn(String fieldKey) {
        String col = MAP.get(fieldKey);
        if (col == null) throw new UnknownFieldException(fieldKey);
        return col;
    }
}
