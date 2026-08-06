package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.scenario.Scenario;

import java.time.Instant;

/**
 * Lightweight scenario listing / compare side projection.
 */
public record ScenarioSummary(
        Long id,
        String name,
        String schemeId,
        Long totalCount,
        Instant createdAt
) {
    public static ScenarioSummary from(Scenario s) {
        return new ScenarioSummary(
                s.getId(),
                s.getName(),
                s.getSchemeId(),
                s.getTotalCount(),
                s.getCreatedAt());
    }
}
