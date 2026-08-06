package gov.rajasthan.smart.srse.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.Ast;
import gov.rajasthan.smart.srse.execution.BreakdownRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit tests for {@link ScenarioService} — real ObjectMapper so
 * JSON round-tripping of Ast / BreakdownRow is genuinely exercised.
 */
@ExtendWith(MockitoExtension.class)
class ScenarioServiceTest {

    @Mock
    private ScenarioRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScenarioService service;

    @BeforeEach
    void setUp() {
        service = new ScenarioService(repository, objectMapper);

        AtomicLong idSeq = new AtomicLong(1);
        // lenient: not every test exercises save
        lenient().when(repository.save(any(Scenario.class))).thenAnswer(invocation -> {
            Scenario s = invocation.getArgument(0);
            if (s.getId() != null) {
                return s;
            }
            return new Scenario(
                    idSeq.getAndIncrement(),
                    s.getName(),
                    s.getSchemeId(),
                    s.getRulesetJson(),
                    s.getTotalCount(),
                    s.getBreakdownJson(),
                    s.getCreatedAt(),
                    s.getCreatedBy());
        });
    }

    /** Ekal Naari–shaped nested AST (mirrors RuleCompilerTest). */
    private static Ast.PredicateSpec ekalNaariSpec() {
        var income = new Ast.PredicateNode("annual_income_total", Ast.Operator.LT, 48000);
        var ration = new Ast.PredicateNode("ration_card_category", Ast.Operator.IN,
                List.of("BPL", "ANTYODAYA"));
        var community = new Ast.PredicateNode("community", Ast.Operator.IN,
                List.of("SAHARIYA", "KATHODI", "KHAIRWA"));
        var incomeOrExempt = new Ast.GroupNode(Ast.BoolOp.OR,
                List.of(income, ration, community));

        var root = new Ast.GroupNode(Ast.BoolOp.AND, List.of(
                new Ast.PredicateNode("marital_status", Ast.Operator.EQ, "DIVORCED"),
                new Ast.PredicateNode("gender", Ast.Operator.EQ, "FEMALE"),
                new Ast.PredicateNode("age_years", Ast.Operator.GTE, 18),
                new Ast.PredicateNode("is_domicile_holder", Ast.Operator.IS_TRUE),
                incomeOrExempt
        ));
        return new Ast.PredicateSpec(root);
    }

    @Test
    void createScenarioThenLoadRulesetRoundTripsAst() {
        Ast.PredicateSpec original = ekalNaariSpec();

        Scenario saved = service.createScenario("Ekal baseline", "EKAL_NAARI", original);
        when(repository.findById(saved.getId())).thenReturn(Optional.of(saved));

        Ast.PredicateSpec loaded = service.loadRuleset(saved.getId());
        assertEquals(original, loaded);
    }

    @Test
    void recordResultsStoresTotalAndBreakdownRoundTrippedViaCompare() throws Exception {
        Scenario a = unevaluated(1L, "A");
        Scenario b = unevaluated(2L, "B");
        when(repository.findById(1L)).thenReturn(Optional.of(a));
        when(repository.findById(2L)).thenReturn(Optional.of(b));

        List<BreakdownRow> breakdownA = List.of(
                new BreakdownRow("JAIPUR", "F", "18-59", 100),
                new BreakdownRow("UDAIPUR", "F", "18-59", 40));
        List<BreakdownRow> breakdownB = List.of(
                new BreakdownRow("JAIPUR", "F", "18-59", 120),
                new BreakdownRow("UDAIPUR", "F", "18-59", 35));

        service.recordResults(1L, 140, breakdownA);
        service.recordResults(2L, 155, breakdownB);

        assertEquals(140L, a.getTotalCount());
        assertEquals(objectMapper.writeValueAsString(breakdownA), a.getBreakdownJson());
        assertEquals(155L, b.getTotalCount());

        ScenarioComparison comparison = service.compare(1L, 2L);
        assertEquals(15L, comparison.totalCountDelta());
        assertEquals(2, comparison.breakdownDeltas().size());
    }

    @Test
    void compareComputesPerCellDeltasIncludingMissingSideAsZero() {
        Scenario a = evaluated(1L, "A", 100L, List.of(
                new BreakdownRow("JAIPUR", "F", "18-59", 100),
                new BreakdownRow("KOTA", "M", "60+", 20)));
        Scenario b = evaluated(2L, "B", 130L, List.of(
                new BreakdownRow("JAIPUR", "F", "18-59", 110),
                new BreakdownRow("AJMER", "F", "18-59", 25)));
        when(repository.findById(1L)).thenReturn(Optional.of(a));
        when(repository.findById(2L)).thenReturn(Optional.of(b));

        ScenarioComparison comparison = service.compare(1L, 2L);

        assertEquals(30L, comparison.totalCountDelta()); // 130 - 100

        BreakdownDelta jaipur = findDelta(comparison, "JAIPUR", "F", "18-59");
        assertEquals(100L, jaipur.countA());
        assertEquals(110L, jaipur.countB());
        assertEquals(10L, jaipur.delta());

        // Present only in A → countB treated as 0
        BreakdownDelta kota = findDelta(comparison, "KOTA", "M", "60+");
        assertEquals(20L, kota.countA());
        assertEquals(0L, kota.countB());
        assertEquals(-20L, kota.delta());

        // Present only in B → countA treated as 0
        BreakdownDelta ajmer = findDelta(comparison, "AJMER", "F", "18-59");
        assertEquals(0L, ajmer.countA());
        assertEquals(25L, ajmer.countB());
        assertEquals(25L, ajmer.delta());

        assertEquals(3, comparison.breakdownDeltas().size());
    }

    @Test
    void compareThrowsWhenEitherScenarioNotEvaluated() {
        Scenario evaluated = evaluated(1L, "A", 50L, List.of(
                new BreakdownRow("JAIPUR", "F", "18-59", 50)));
        Scenario unevaluated = unevaluated(2L, "B");
        when(repository.findById(1L)).thenReturn(Optional.of(evaluated));
        when(repository.findById(2L)).thenReturn(Optional.of(unevaluated));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.compare(1L, 2L));
        assertTrue(ex.getMessage().contains("2"));
        assertTrue(ex.getMessage().contains("not been evaluated"));

        // A unevaluated, B evaluated
        when(repository.findById(1L)).thenReturn(Optional.of(unevaluated(1L, "A")));
        when(repository.findById(2L)).thenReturn(Optional.of(
                evaluated(2L, "B", 10L, List.of())));
        IllegalStateException exA = assertThrows(IllegalStateException.class,
                () -> service.compare(1L, 2L));
        assertTrue(exA.getMessage().contains("1"));
    }

    @Test
    void unknownIdThrowsScenarioNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ScenarioNotFoundException.class, () -> service.getScenario(999L));
        assertThrows(ScenarioNotFoundException.class, () -> service.loadRuleset(999L));
        assertThrows(ScenarioNotFoundException.class,
                () -> service.recordResults(999L, 1, List.of()));
    }

    private Scenario unevaluated(Long id, String name) {
        return new Scenario(id, name, "SCHEME", "{\"root\":null}",
                null, null, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private Scenario evaluated(Long id, String name, Long totalCount,
                               List<BreakdownRow> breakdown) {
        try {
            String json = objectMapper.writeValueAsString(breakdown);
            return new Scenario(id, name, "SCHEME", "{\"root\":null}",
                    totalCount, json, Instant.parse("2026-01-01T00:00:00Z"), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BreakdownDelta findDelta(ScenarioComparison comparison,
                                            String district, String gender, String ageBand) {
        return comparison.breakdownDeltas().stream()
                .filter(d -> district.equals(d.district())
                        && gender.equals(d.gender())
                        && ageBand.equals(d.ageBand()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No delta for " + district + "/" + gender + "/" + ageBand));
    }
}
