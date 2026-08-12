package gov.rajasthan.smart.srse.decision;

import gov.rajasthan.smart.srse.scenario.Scenario;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight scenario listing / compare side projection.
 */
public record ScenarioSummary(
        Long id,
        String name,
        List<Long> schemeIds,
        Long totalCount,
        Instant createdAt
) {
    public static ScenarioSummary from(Scenario s) {
        return new ScenarioSummary(
                s.getId(),
                s.getName(),
                List.copyOf(s.getSchemeIds()),
                s.getTotalCount(),
                s.getCreatedAt());
    }
}
