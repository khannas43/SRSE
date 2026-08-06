package gov.rajasthan.smart.srse.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.execution.BreakdownRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operational-plane scenario store: persist ruleset snapshots, record evaluation
 * results, and compare evaluated scenarios.
 */
@Service
public class ScenarioService {

    private static final TypeReference<List<BreakdownRow>> BREAKDOWN_LIST =
            new TypeReference<>() {};

    private final ScenarioRepository repository;
    private final ObjectMapper objectMapper;

    public ScenarioService(ScenarioRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a new scenario with the given ruleset (unevaluated).
     */
    public Scenario createScenario(String name, String schemeId, Ast.PredicateSpec ruleset) {
        String rulesetJson;
        try {
            rulesetJson = objectMapper.writeValueAsString(ruleset);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ruleset", e);
        }
        Scenario scenario = new Scenario(
                null, name, schemeId, rulesetJson,
                null, null, Instant.now(), null);
        return repository.save(scenario);
    }

    /**
     * Attach computed evaluation results to an existing scenario.
     */
    public Scenario recordResults(Long scenarioId, long totalCount, List<BreakdownRow> breakdown) {
        Scenario scenario = getScenario(scenarioId);
        String breakdownJson;
        try {
            breakdownJson = objectMapper.writeValueAsString(breakdown);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize breakdown", e);
        }
        scenario.applyResults(totalCount, breakdownJson);
        return repository.save(scenario);
    }

    /**
     * Deserialize and return the ruleset stored on a scenario.
     */
    public Ast.PredicateSpec loadRuleset(Long scenarioId) {
        Scenario scenario = getScenario(scenarioId);
        try {
            return objectMapper.readValue(scenario.getRulesetJson(), Ast.PredicateSpec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize ruleset for scenario " + scenarioId, e);
        }
    }

    /**
     * Load a scenario by id, or throw if absent.
     */
    public Scenario getScenario(Long scenarioId) {
        return repository.findById(scenarioId)
                .orElseThrow(() -> new ScenarioNotFoundException(scenarioId));
    }

    /**
     * List scenarios for a scheme, newest first.
     */
    public List<Scenario> listByScheme(String schemeId) {
        return repository.findBySchemeIdOrderByCreatedAtDesc(schemeId);
    }

    /**
     * Compare two evaluated scenarios: total-count delta and per-cell breakdown deltas.
     * A dimension combination present on only one side is treated as count 0 on the other.
     */
    public ScenarioComparison compare(Long scenarioIdA, Long scenarioIdB) {
        Scenario scenarioA = getScenario(scenarioIdA);
        Scenario scenarioB = getScenario(scenarioIdB);

        if (scenarioA.getTotalCount() == null) {
            throw new IllegalStateException(
                    "Scenario " + scenarioIdA + " has not been evaluated yet");
        }
        if (scenarioB.getTotalCount() == null) {
            throw new IllegalStateException(
                    "Scenario " + scenarioIdB + " has not been evaluated yet");
        }

        List<BreakdownRow> rowsA = readBreakdown(scenarioA);
        List<BreakdownRow> rowsB = readBreakdown(scenarioB);

        Map<DimKey, Long> countsA = toCountMap(rowsA);
        Map<DimKey, Long> countsB = toCountMap(rowsB);

        Set<DimKey> allKeys = new HashSet<>();
        allKeys.addAll(countsA.keySet());
        allKeys.addAll(countsB.keySet());

        List<BreakdownDelta> deltas = new ArrayList<>();
        for (DimKey key : allKeys) {
            long countA = countsA.getOrDefault(key, 0L);
            long countB = countsB.getOrDefault(key, 0L);
            deltas.add(new BreakdownDelta(
                    key.district(), key.gender(), key.ageBand(),
                    countA, countB, countB - countA));
        }

        long totalCountDelta = scenarioB.getTotalCount() - scenarioA.getTotalCount();
        return new ScenarioComparison(scenarioA, scenarioB, totalCountDelta, deltas);
    }

    private List<BreakdownRow> readBreakdown(Scenario scenario) {
        if (scenario.getBreakdownJson() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(scenario.getBreakdownJson(), BREAKDOWN_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize breakdown for scenario " + scenario.getId(), e);
        }
    }

    private static Map<DimKey, Long> toCountMap(List<BreakdownRow> rows) {
        Map<DimKey, Long> map = new HashMap<>();
        for (BreakdownRow row : rows) {
            map.put(new DimKey(row.district(), row.gender(), row.ageBand()), row.count());
        }
        return map;
    }

    private record DimKey(String district, String gender, String ageBand) {}
}
