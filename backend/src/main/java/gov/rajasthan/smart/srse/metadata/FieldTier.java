package gov.rajasthan.smart.srse.metadata;

/**
 * Field tiers per CLAUDE.md flat-catalogue contract:
 * {@code TIER_1} — direct column; {@code TIER_2} — same-table expression;
 * {@code TIER_3} — cross-table/relationship/temporal, pre-materialised upstream.
 */
public enum FieldTier {
    TIER_1,
    TIER_2,
    TIER_3
}
