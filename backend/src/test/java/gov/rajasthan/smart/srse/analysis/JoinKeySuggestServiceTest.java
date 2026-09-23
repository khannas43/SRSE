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
                new AnalysisProperties(5, 120, 4, 2, 10, 3, 50_000_000L, 10),
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
    void idToSuffixIdInTopThreeAmongAlphabeticalNoise() {
        List<RegisteredColumn> source = new java.util.ArrayList<>();
        source.add(new RegisteredColumn("id", "bigint", null, false, true));
        source.add(new RegisteredColumn("district", "varchar", null, false, true));
        source.add(new RegisteredColumn("father_name", "varchar", null, false, true));
        for (char c = 'a'; c <= 'z'; c++) {
            source.add(new RegisteredColumn("attr_" + c, "varchar", null, false, true));
        }
        List<RegisteredColumn> target = new java.util.ArrayList<>();
        target.add(new RegisteredColumn("m_id", "bigint", null, false, true));
        target.add(new RegisteredColumn("district", "varchar", null, false, true));
        target.add(new RegisteredColumn("father_name", "varchar", null, false, true));
        for (char c = 'a'; c <= 'z'; c++) {
            target.add(new RegisteredColumn("other_" + c, "varchar", null, false, true));
        }
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(source);
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(target);

        List<JoinKeySuggestion> suggestions = service.suggest(request(false));
        int idIndex = -1;
        for (int i = 0; i < suggestions.size(); i++) {
            if ("id".equals(suggestions.get(i).sourceColumn())
                    && "m_id".equals(suggestions.get(i).targetColumn())) {
                idIndex = i;
                break;
            }
        }
        assertTrue(idIndex >= 0 && idIndex < 3, "id↔m_id must appear in top 3, was index " + idIndex);
    }

    @Test
    void sameFamilyIdentifierPairOutranksCrossFamilyIdentifierPair() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("id", "bigint", null, false, true),
                new RegisteredColumn("district", "varchar", null, false, true),
                new RegisteredColumn("father_name", "varchar", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("m_id", "bigint", null, false, true),
                new RegisteredColumn("bank_id", "varchar", null, false, true),
                new RegisteredColumn("bank_branch_id", "varchar", null, false, true),
                new RegisteredColumn("district", "varchar", null, false, true),
                new RegisteredColumn("father_name", "varchar", null, false, true)));

        List<JoinKeySuggestion> suggestions = service.suggest(request(false));
        int mId = indexOfPair(suggestions, "id", "m_id");
        int bankBranch = indexOfPair(suggestions, "id", "bank_branch_id");
        int bank = indexOfPair(suggestions, "id", "bank_id");
        assertTrue(mId >= 0 && bankBranch >= 0 && bank >= 0);
        assertTrue(mId < bankBranch && mId < bank,
                "id↔m_id (bigint↔bigint) must rank above cross-family identifier pairs");
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

    @Test
    void temporalColumnNeverPairedWithNonTemporal() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("age_band", "varchar", null, false, true),
                new RegisteredColumn("age_years", "integer", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("date_of_birth", "date", null, false, true),
                new RegisteredColumn("account_no", "varchar", null, false, true)));

        List<JoinKeySuggestion> suggestions = service.suggest(request(false));
        assertTrue(suggestions.stream().noneMatch(s ->
                s.targetColumn().equals("date_of_birth") || s.sourceColumn().equals("date_of_birth")));
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
    void distinctnessFailureDegradesToMetadataOnly() {
        when(registry.listColumns(CATALOG, SCHEMA, SRC)).thenReturn(List.of(
                new RegisteredColumn("id", "bigint", null, false, true),
                new RegisteredColumn("district", "varchar", null, false, true)));
        when(registry.listColumns(CATALOG, SCHEMA, TGT)).thenReturn(List.of(
                new RegisteredColumn("m_id", "bigint", null, false, true),
                new RegisteredColumn("district", "varchar", null, false, true)));
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenThrow(new RuntimeException("Query exceeded maximum time limit of 30.00s"));
        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(0.23);

        List<JoinKeySuggestion> suggestions = service.suggest(request(true));
        assertFalse(suggestions.isEmpty());
        assertTrue(suggestions.stream().anyMatch(s ->
                        s.reason().contains("Same column name")
                                || s.reason().contains("Identifier column match")
                                || s.reason().contains("Compatible types")
                                || s.reason().contains("sampled source values")),
                "Distinctness timeout must degrade (metadata ranking ± probe), not abort the request");
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
