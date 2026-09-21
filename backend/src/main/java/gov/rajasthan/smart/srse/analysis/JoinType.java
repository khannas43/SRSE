package gov.rajasthan.smart.srse.analysis;

import java.util.Set;

/** Two-table match join shape for {@link RecordMatchService}. */
public enum JoinType {
    INNER(Set.of()),
    LEFT(Set.of("tgt")),
    RIGHT(Set.of("src")),
    FULL(Set.of("src", "tgt"));

    private final Set<String> nullableSideAliases;

    JoinType(Set<String> nullableSideAliases) {
        this.nullableSideAliases = nullableSideAliases;
    }

    /** SQL keyword(s) before {@code JOIN} — {@link #INNER} omits the qualifier for backward-compatible SQL. */
    public String joinKeyword() {
        return this == INNER ? "JOIN" : name() + " JOIN";
    }

    /** Side aliases ({@code src} / {@code tgt}) that may be NULL on unmatched rows. */
    public Set<String> nullableSides() {
        return nullableSideAliases;
    }

    public boolean preservesSourceSide() {
        return this == LEFT || this == FULL;
    }

    public boolean preservesTargetSide() {
        return this == RIGHT || this == FULL;
    }

    public static JoinType effective(JoinType joinType) {
        return joinType == null ? INNER : joinType;
    }
}
