package gov.rajasthan.smart.srse.analysis;

/**
 * Which side of the officer's mental model is the hub table in a multi-target
 * run. Affects merged-grid column prefixes only — every sub-match still builds
 * the hub as {@code src} in SQL (see {@link MultiTargetRecordMatchService}).
 */
public enum HubSide {
    SOURCE,
    TARGET
}
