package gov.rajasthan.smart.srse.analysis;

import gov.rajasthan.smart.srse.execution.GuardrailProperties;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService.RegisteredColumn;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinKeySuggestServiceTest {

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "gold";
    private static final String SRC = "src_tbl";
    private static final String TGT = "tgt_tbl";

    @Mock
    private LakehouseRegistryService registry;
    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;
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
    void unregisteredTableRejectedByRegistry() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC))
                .thenThrow(new IllegalArgumentException("Table is not registered for SRSE"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.suggest(request(false)));
    }

    @Test
    void hiddenColumnsNeverSuggested() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("visible_id", "bigint", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("visible_id", "bigint", null, false, true)));

        List<JoinKeySuggestion> suggestions = service.suggest(request(false));
        assertEquals(1, suggestions.size());
        assertEquals("visible_id", suggestions.get(0).targetColumn());
    }

    @Test
    void unknownFamilyExcluded() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("row_col", "row(x bigint)", null, false, true),
                new RegisteredColumn("acct", "bigint", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("acct", "bigint", null, false, true)));

        List<JoinKeySuggestion> suggestions = service.suggest(request(false));
        assertEquals(1, suggestions.size());
        assertEquals("acct", suggestions.get(0).sourceColumn());
    }

    @Test
    void exactNameSameFamilyRanksFirst() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("z_col", "varchar", null, false, true),
                new RegisteredColumn("member_id", "bigint", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("member_id", "bigint", null, false, true),
                new RegisteredColumn("z_col", "varchar", null, false, true)));

        List<JoinKeySuggestion> suggestions = service.suggest(request(false));
        assertEquals("member_id", suggestions.get(0).sourceColumn());
        assertEquals("member_id", suggestions.get(0).targetColumn());
        assertTrue(suggestions.get(0).reason().contains("Same column name"));
    }

    @Test
    void registeredNonFuzzyBeatsNameSubstringGuess() {
        assertFalse(AnalysisColumnMatchHeuristics.groupWouldBeFuzzy(List.of(
                new AnalysisColumnMatchHeuristics.ColumnMetaRef(
                        "full_name",
                        Optional.of(metadata(CATALOG, SCHEMA, SRC, "full_name", false))))));
        assertTrue(AnalysisColumnMatchHeuristics.nameSubstringGuess("full_name"));
    }

    @Test
    void probeFailureDegradesToMetadataOnly() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("id", "bigint", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("id", "bigint", null, false, true)));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(Map.of("id", 1.0)));
        when(jdbc.queryForObject(anyString(), eq(Double.class)))
                .thenThrow(new RuntimeException("Presto down"));

        List<JoinKeySuggestion> suggestions = service.suggest(request(true));
        assertEquals(1, suggestions.size());
        assertTrue(suggestions.get(0).reason().contains("Same column name"));
    }

    @Test
    void metadataOnlyDoesNotQueryPresto() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("id", "bigint", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("id", "bigint", null, false, true)));

        service.suggest(request(false));
        verify(jdbc, never()).query(anyString(), any(RowMapper.class));
        verify(jdbc, never()).queryForObject(anyString(), eq(Double.class));
    }

    private static SuggestJoinKeysRequest request(boolean probe) {
        return new SuggestJoinKeysRequest(CATALOG, SCHEMA, SRC, CATALOG, SCHEMA, TGT, probe);
    }

    private static AnalysisColumnMetadata metadata(
            String catalog, String schema, String table, String column, boolean fuzzy) {
        return new AnalysisColumnMetadata(
                1L, new QualifiedColumn(catalog, schema, table, column), null, fuzzy, true);
    }
}
