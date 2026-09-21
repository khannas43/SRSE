package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.compiler.SqlTypeFamily;
import gov.rajasthan.smart.srse.compiler.TypeCoercion;
import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinKeySuggestProbeTest {

    private static final String CATALOG = "iceberg";
    private static final String SCHEMA = "srse";
    private static final String SRC = "beneficiary";
    private static final String TGT = "tbl_txn_bankdtl";
    private static final String TGT_SCHEMA = "silver_txn";
    private static final String TGT_CATALOG = "iceberg_silver";

    @Mock
    private gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService registry;
    @Mock
    private gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository columnMetadata;
    @Mock
    private JdbcTemplate jdbc;

    private JoinKeySuggestService service;

    @BeforeEach
    void setUp() {
        service = new JoinKeySuggestService(
                registry,
                columnMetadata,
                new AnalysisProperties(5, 120, 4, 2, 10),
                new GuardrailProperties(1000, 30, 50),
                jdbc);
    }

    @Test
    void probeSqlSamplesSourceOnlyNotTarget() {
        QualifiedTable source = new QualifiedTable(CATALOG, SCHEMA, SRC);
        QualifiedTable target = new QualifiedTable(TGT_CATALOG, TGT_SCHEMA, TGT);
        RegisteredColumn sourceCol = new RegisteredColumn("id", "bigint", null, false, true);
        RegisteredColumn targetCol = new RegisteredColumn("m_id", "bigint", null, false, true);
        TypeCoercion.Aligned aligned = TypeCoercion.align(
                "s.v", SqlTypeFamily.NUMBER, "t.v", SqlTypeFamily.NUMBER, CompareAs.AUTO);

        String sql = JoinKeySuggestService.buildProbeOverlapSql(source, target, sourceCol, targetCol, aligned);

        assertEquals(1, sql.split("TABLESAMPLE BERNOULLI", -1).length - 1,
                "Exactly one TABLESAMPLE clause (source side only)");
        assertTrue(sql.contains("FROM " + source.qualifiedName() + " TABLESAMPLE"));
        assertTrue(sql.contains("FROM " + target.qualifiedName() + " WHERE"));
        assertFalse(sql.contains(target.qualifiedName() + " TABLESAMPLE"));
        assertTrue(sql.contains("approx_distinct(CASE WHEN t.v IS NOT NULL THEN s.v END)"));
        assertFalse(sql.contains("COUNT(t.v)"));
    }

    @Test
    void highDistinctnessKeyOutranksLowCardinalityAttributeWithProbe() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("district", "varchar", null, false, true),
                new RegisteredColumn("id", "bigint", null, false, true)));
        when(registry.listColumns(TGT_CATALOG, TGT_SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("district", "varchar", null, false, true),
                new RegisteredColumn("m_id", "bigint", null, false, true)));

        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(Map.of(
                "district", 0.00008,
                "id", 1.03)));

        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(0.23);

        List<JoinKeySuggestion> suggestions = service.suggest(new SuggestJoinKeysRequest(
                CATALOG, SCHEMA, SRC, TGT_CATALOG, TGT_SCHEMA, TGT, true));

        assertFalse(suggestions.isEmpty());
        assertEquals("id", suggestions.get(0).sourceColumn());
        assertTrue(suggestions.get(0).targetColumn().equals("m_id")
                || suggestions.get(0).sourceColumn().equals("id"));
        int districtIndex = indexOfPair(suggestions, "district", "district");
        int idIndex = indexOfPair(suggestions, "id", "m_id");
        if (districtIndex >= 0 && idIndex >= 0) {
            assertTrue(idIndex < districtIndex, "id↔m_id must rank above district↔district");
        }
    }

    @Test
    void applyDistinctnessFloorsAttributeColumns() {
        int idScore = JoinKeySuggestService.applyDistinctnessToScore(400, 1.03);
        int districtScore = JoinKeySuggestService.applyDistinctnessToScore(400, 0.00008);
        assertTrue(idScore > districtScore);
        assertTrue(districtScore <= 49);
    }

    @Test
    void midCardinalityTrueDistinctness010IsFloored() {
        assertTrue(JoinKeySuggestService.applyDistinctnessToScore(400, 0.1) <= 49,
                "True ratio 0.1 is below the key floor — must not rank like a key");
    }

    @Test
    void sampledDistinctnessInflationWouldWronglyPromoteMidCardinalityColumn() {
        // If distinctness were measured on a 10% sample, ~0.1 full often reads ~1.0 — would pass as a key.
        assertTrue(JoinKeySuggestService.applyDistinctnessToScore(400, 1.0) > 400);
        assertTrue(JoinKeySuggestService.applyDistinctnessToScore(400, 0.1) <= 49);
    }

    @Test
    void distinctnessSqlScansFullSourceTableNotSampled() {
        QualifiedTable source = new QualifiedTable(CATALOG, SCHEMA, SRC);
        List<RegisteredColumn> cols = List.of(
                new RegisteredColumn("age_years", "integer", null, false, true));
        String sql = JoinKeySuggestService.buildSourceDistinctnessSql(source, cols);
        assertFalse(sql.contains("TABLESAMPLE"), "Distinctness must use full table, not Bernoulli sample");
        assertTrue(sql.contains("FROM " + source.qualifiedName()));
    }

    private static int indexOfPair(List<JoinKeySuggestion> list, String src, String tgt) {
        for (int i = 0; i < list.size(); i++) {
            JoinKeySuggestion s = list.get(i);
            if (s.sourceColumn().equals(src) && s.targetColumn().equals(tgt)) {
                return i;
            }
        }
        return -1;
    }
}
