package gov.rajasthan.smart.srse.scenario;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persisted scenario: a saved ruleset (Ast.PredicateSpec serialized as JSON) plus its most recent computed results, if evaluated.
 * A scenario can be tagged to more than one scheme (e.g. a broadly-applicable
 * eligibility combination reused across related pension schemes).
 */
@Entity
@Table(name = "scenario")
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ElementCollection
    @CollectionTable(name = "scenario_scheme_tag", joinColumns = @JoinColumn(name = "scenario_id"))
    @Column(name = "scheme_id", nullable = false)
    private Set<Long> schemeIds = new LinkedHashSet<>();

    @Lob
    @Column(name = "ruleset_json", nullable = false)
    private String rulesetJson;

    /** Null until the scenario has been evaluated. */
    @Column(name = "total_count")
    private Long totalCount;

    @Lob
    @Column(name = "breakdown_json")
    private String breakdownJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Nullable — real SSO identity arrives in a later build stage. */
    @Column(name = "created_by")
    private String createdBy;

    protected Scenario() {
    }

    public Scenario(ScenarioData data) {
        this.id = data.id();
        this.name = data.name();
        this.schemeIds = new LinkedHashSet<>(data.schemeIds());
        this.rulesetJson = data.rulesetJson();
        this.totalCount = data.totalCount();
        this.breakdownJson = data.breakdownJson();
        this.createdAt = data.createdAt();
        this.createdBy = data.createdBy();
    }

    public record ScenarioData(
            Long id,
            String name,
            Set<Long> schemeIds,
            String rulesetJson,
            Long totalCount,
            String breakdownJson,
            Instant createdAt,
            String createdBy
    ) {
    }

    /**
     * Controlled mutation for persisting evaluation results.
     * JPA entities need mutability for persistence-context updates;
     * open setters are intentionally avoided.
     */
    public void applyResults(Long totalCount, String breakdownJson) {
        this.totalCount = totalCount;
        this.breakdownJson = breakdownJson;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<Long> getSchemeIds() {
        return schemeIds;
    }

    public String getRulesetJson() {
        return rulesetJson;
    }

    public Long getTotalCount() {
        return totalCount;
    }

    public String getBreakdownJson() {
        return breakdownJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
