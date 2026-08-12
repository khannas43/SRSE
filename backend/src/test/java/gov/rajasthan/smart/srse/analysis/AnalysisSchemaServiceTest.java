package gov.rajasthan.smart.srse.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Mocked-JdbcTemplate unit tests — no live Presto, same style as
 * {@code ExecutionServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisSchemaServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private AnalysisSchemaService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisSchemaService(jdbc);
        lenient().when(jdbc.execute(ArgumentMatchers.<ConnectionCallback<String>>any())).thenReturn("srse");
    }

    @Test
    void listTablesQueriesInformationSchemaScopedToLiveSchema() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("srse")))
                .thenReturn(List.of("beneficiary"));

        List<String> tables = service.listTables();

        assertEquals(List.of("beneficiary"), tables);
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).queryForList(sqlCap.capture(), eq(String.class), eq("srse"));
        assertTrue(sqlCap.getValue().contains("information_schema.tables"), sqlCap.getValue());
    }

    @Test
    void listColumnsValidatesTableFirst() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("srse")))
                .thenReturn(List.of("beneficiary"));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<AnalysisSchemaService.ColumnInfo>>any(),
                eq("srse"), eq("beneficiary")))
                .thenReturn(List.of(new AnalysisSchemaService.ColumnInfo("father_name", "varchar")));

        List<AnalysisSchemaService.ColumnInfo> cols = service.listColumns("beneficiary");

        assertEquals(1, cols.size());
        assertEquals("father_name", cols.get(0).name());
    }

    @Test
    void listColumnsRejectsUnknownTable() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("srse")))
                .thenReturn(List.of("beneficiary"));

        assertThrows(IllegalArgumentException.class, () -> service.listColumns("bogus_table"));
    }

    @Test
    void validateColumnRejectsUnknownColumn() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("srse")))
                .thenReturn(List.of("beneficiary"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("srse"), eq("beneficiary"), eq("bogus_col")))
                .thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.validateColumn("beneficiary", "bogus_col"));
    }

    @Test
    void validateColumnAcceptsKnownColumn() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("srse")))
                .thenReturn(List.of("beneficiary"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("srse"), eq("beneficiary"), eq("father_name")))
                .thenReturn(List.of("father_name"));

        service.validateColumn("beneficiary", "father_name");
    }
}
