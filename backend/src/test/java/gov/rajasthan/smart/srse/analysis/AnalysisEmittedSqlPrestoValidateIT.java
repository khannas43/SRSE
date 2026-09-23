package gov.rajasthan.smart.srse.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs emitted Analysis SQL through Presto's analyzer ({@code EXPLAIN (TYPE VALIDATE)}).
 *
 * <p>Parse-only tests cannot catch alias scope, unsupported UNNEST placement, or missing
 * functions — this can, without reading data.
 *
 * <p>Not part of the default build. With the local stack up:
 * {@code SRSE_PRESTO_INTEGRATION=true mvn -pl backend test -Dtest=AnalysisEmittedSqlPrestoValidateIT}
 */
@EnabledIfEnvironmentVariable(named = "SRSE_PRESTO_INTEGRATION", matches = "true")
class AnalysisEmittedSqlPrestoValidateIT {

    private static final String JDBC_URL = System.getenv().getOrDefault(
            "SRSE_PRESTO_JDBC_URL", "jdbc:presto://localhost:8081/iceberg/srse");
    private static final String CATALOG = "iceberg";
    private static final String SCHEMA = "srse";
    private static final String TABLE = "beneficiary";

    private JdbcTemplate presto;
    private RecordMatchService service;

    @BeforeEach
    void setUp() {
        assumeTrue(prestoReachable(), () -> "Presto not reachable at " + JDBC_URL + " — start docker compose first");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.facebook.presto.jdbc.PrestoDriver");
        ds.setUrl(JDBC_URL);
        ds.setUsername("srse");
        presto = new JdbcTemplate(ds);

        LakehouseRegistryService registry = mock(LakehouseRegistryService.class);
        AnalysisColumnMetadataRepository columnMetadata = mock(AnalysisColumnMetadataRepository.class);
        lenient().when(columnMetadata.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(registry.hasColumns(any(), any())).thenReturn(true);
        when(registry.describeColumns(any(), any())).thenAnswer(inv -> {
            Map<String, RegisteredColumn> described = new LinkedHashMap<>();
            for (Object column : inv.getArgument(1, List.class)) {
                String name = String.valueOf(column);
                described.put(name, new RegisteredColumn(name, "varchar(50)", null, false, true));
            }
            return described;
        });

        FieldResolver fields = fieldKey -> {
            if ("age_years".equals(fieldKey)) {
                return CATALOG + "." + SCHEMA + "." + TABLE + ".age_years";
            }
            throw new FieldResolver.UnknownFieldException(fieldKey);
        };
        service = new RecordMatchService(
                presto, registry, new GuardrailProperties(1000, 120, 50), fields, columnMetadata,
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L), new ObjectMapper());
    }

    private static boolean prestoReachable() {
        try {
            DriverManagerDataSource probe = new DriverManagerDataSource();
            probe.setDriverClassName("com.facebook.presto.jdbc.PrestoDriver");
            probe.setUrl(JDBC_URL);
            probe.setUsername("srse");
            new JdbcTemplate(probe).queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static MatchCriterion col(String column) {
        return new MatchCriterion(CATALOG, SCHEMA, TABLE, column, null);
    }

    private static MatchGroup group(List<MatchCriterion> source, List<MatchCriterion> target,
                                    GroupMode mode, Double threshold) {
        return new MatchGroup(source, target, mode, threshold, null);
    }

    private void explainValidate(RecordMatchRequest req) {
        RecordMatchService.MatchQuery query = service.planMatch(req);
        assertDoesNotThrow(() -> runExplainValidate(presto, query.sql(), query.params()));
    }

    private static void runExplainValidate(JdbcTemplate jdbc, String sql, List<Object> params) throws Exception {
        jdbc.execute((Connection connection) -> {
            try (PreparedStatement ps = connection.prepareStatement("EXPLAIN (TYPE VALIDATE) " + sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                ps.execute();
            }
            return null;
        });
    }

    @Test
    void mismatchOnlyLeftJoinWithComparisonValidatesOnPresto() {
        explainValidate(new RecordMatchRequest(
                List.of(), List.of(), null, null,
                List.of(group(List.of(col("id")), List.of(col("id")), GroupMode.COMBINE, null)),
                false, null, null, JoinType.LEFT,
                List.of(ComparisonGroup.of(col("district"), col("district"))),
                true));
    }

    /** Subset of {@link EmittedSqlParsesTest#placeholderCountMatchesParamListForAllDocumentedShapes}. */
    @Test
    void documentedShapesValidateOnPresto() {
        List<RecordMatchRequest> cases = new ArrayList<>();
        cases.add(new RecordMatchRequest(
                List.of(), List.of(), null, null,
                List.of(group(List.of(col("id")), List.of(col("id")), GroupMode.COMBINE, null)),
                false, null, null, null));
        cases.add(new RecordMatchRequest(
                List.of(), List.of(), null, null,
                List.of(group(List.of(col("father_name")),
                        List.of(col("mother_name"), col("father_name")),
                        GroupMode.COMBINE, null)),
                false, null, null, null));
        cases.add(new RecordMatchRequest(
                List.of(), List.of(), null, null,
                List.of(group(List.of(col("id")),
                        List.of(col("id"), col("district")),
                        GroupMode.ANY_OF, null)),
                false, null, null, null));
        ComparisonGroup fuzzyCompare = new ComparisonGroup(
                List.of(col("father_name")),
                List.of(col("father_name")),
                GroupMode.COMBINE, 85.0, null);
        cases.add(new RecordMatchRequest(
                List.of(), List.of(), null, null,
                List.of(group(List.of(col("id")), List.of(col("id")), GroupMode.COMBINE, null)),
                false, null, null, JoinType.LEFT,
                List.of(ComparisonGroup.of(col("district"), col("district"))),
                true));
        cases.add(new RecordMatchRequest(
                List.of(), List.of(), null, null,
                List.of(group(List.of(col("id")), List.of(col("id")), GroupMode.COMBINE, null)),
                false, null, null, JoinType.LEFT,
                List.of(fuzzyCompare),
                true));
        for (RecordMatchRequest req : cases) {
            explainValidate(req);
        }
    }
}
