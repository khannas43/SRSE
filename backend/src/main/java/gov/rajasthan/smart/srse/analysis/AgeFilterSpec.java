package gov.rajasthan.smart.srse.analysis;

/**
 * Optional age-range filter. {@code unit} is one of {@code DAYS}, {@code
 * MONTHS}, {@code YEARS} — {@code minAge}/{@code maxAge} are in that unit.
 * Bounds are inclusive and applied against the catalogue's registered
 * {@code age_years} field (resolved via {@link gov.rajasthan.smart.srse.compiler.FieldResolver},
 * the same admin-mapped column the Rule Engine uses) on BOTH source and
 * target rows — not an ad hoc table/column pick, since this tab's own
 * schema-introspection allow-list has no notion of "the age column."
 */
public record AgeFilterSpec(double minAge, double maxAge, String unit) {
}
