package gov.rajasthan.smart.srse.analysis;

/**
 * How the two sides of a {@link MatchGroup} are compared when either side
 * holds more than one column.
 *
 * <p>{@link #COMBINE} folds the many side into one synthetic value — the
 * officer's {@code full_name} against a target's {@code first_name} +
 * {@code last_name}. {@link #ANY_OF} matches when the other side equals
 * <em>any</em> of the many side's columns — one account number against a
 * target's {@code ja_id} or {@code legacy_id}.
 *
 * <p>ANY_OF is emitted with {@code UNNEST}, never as {@code OR} in the JOIN's
 * ON clause: a disjunctive ON drops Presto to a nested loop, which is the
 * cross-product failure {@link RecordMatchService} already records having
 * removed once.
 *
 * <p>With one column on each side the two modes are identical, and both emit
 * exactly what a plain criterion pair emitted before groups existed.
 */
public enum GroupMode {
    COMBINE,
    ANY_OF
}
