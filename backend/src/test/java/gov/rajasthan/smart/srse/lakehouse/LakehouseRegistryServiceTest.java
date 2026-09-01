package gov.rajasthan.smart.srse.lakehouse;

import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadata;
import gov.rajasthan.smart.srse.metadata.AnalysisColumnMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakehouseRegistryServiceTest {

    private static final String CATALOG = "iceberg_data";
    private static final String SCHEMA = "jan_aadhar_data_txn";
    private static final String TABLE = "tbl_txn_bankdtl";

    @Mock
    private RegisteredTableRepository registrations;

    @Mock
    private AnalysisColumnMetadataRepository columnMetadata;

    @Mock
    private LakehouseBrowseService browse;

    private LakehouseRegistryService service;

    @BeforeEach
    void setUp() {
        service = new LakehouseRegistryService(registrations, columnMetadata, browse);
    }

    private void stubRegistered() {
        lenient().when(registrations.existsByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(true);
    }

    private void stubLiveColumns(String... names) {
        lenient().when(browse.listColumns(CATALOG, SCHEMA, TABLE)).thenReturn(
                List.of(names).stream()
                        .map(n -> new LakehouseBrowseService.ColumnInfo(n, "varchar"))
                        .toList());
    }

    /** A typo'd table must never enter the registry and become an officer-visible dead end. */
    @Test
    void registerValidatesAgainstTheLiveLakehouseFirst() {
        doThrow(new IllegalArgumentException("Unknown table"))
                .when(browse).validateTable(CATALOG, SCHEMA, "tbl_typo");

        assertThrows(IllegalArgumentException.class,
                () -> service.register(CATALOG, SCHEMA, "tbl_typo", "GOLD"));
        verify(registrations, never()).save(any());
    }

    @Test
    void registerNormalisesLayerToUpperCase() {
        when(registrations.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(Optional.empty());
        when(registrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisteredTable saved = service.register(CATALOG, SCHEMA, TABLE, " silver ");

        assertEquals("SILVER", saved.getLayer());
    }

    @Test
    void registerBlankLayerStoresNull() {
        when(registrations.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(Optional.empty());
        when(registrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertNull(service.register(CATALOG, SCHEMA, TABLE, "  ").getLayer());
    }

    /** Re-registering is an update of the layer tag, not a duplicate row. */
    @Test
    void registeringAnAlreadyRegisteredTableUpdatesItsLayer() {
        RegisteredTable existing = new RegisteredTable(7L, CATALOG, SCHEMA, TABLE, "SILVER");
        when(registrations.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(Optional.of(existing));
        when(registrations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisteredTable saved = service.register(CATALOG, SCHEMA, TABLE, "GOLD");

        assertEquals(7L, saved.getId());
        assertEquals("GOLD", saved.getLayer());
    }

    /** Registering a table exposes all its columns; metadata only decorates. */
    @Test
    void listColumnsReturnsEveryLiveColumnWhenNoneAreCurated() {
        stubRegistered();
        stubLiveColumns("bank_id", "m_id", "account_no", "bank_branch_id");
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(List.of());

        List<LakehouseRegistryService.RegisteredColumn> columns =
                service.listColumns(CATALOG, SCHEMA, TABLE);

        assertEquals(List.of("bank_id", "m_id", "account_no", "bank_branch_id"),
                columns.stream().map(LakehouseRegistryService.RegisteredColumn::name).toList());
    }

    @Test
    void listColumnsAppliesBusinessNameAndFuzzyFlag() {
        stubRegistered();
        stubLiveColumns("bank_id", "account_no");
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(List.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, TABLE, "account_no"),
                        "Account Number", true, true)));

        LakehouseRegistryService.RegisteredColumn account = service.listColumns(CATALOG, SCHEMA, TABLE)
                .stream().filter(c -> c.name().equals("account_no")).findFirst().orElseThrow();

        assertEquals("Account Number", account.businessName());
        assertTrue(account.fuzzyMatchable());
    }

    @Test
    void listColumnsHidesColumnsTheAdminOptedOut() {
        stubRegistered();
        stubLiveColumns("bank_id", "m_id");
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(List.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, TABLE, "m_id"),
                        null, false, false)));

        assertEquals(List.of("bank_id"), service.listColumns(CATALOG, SCHEMA, TABLE).stream()
                .map(LakehouseRegistryService.RegisteredColumn::name).toList());
    }

    /**
     * A column that vanished upstream must disappear from the officer's
     * dropdown rather than linger as a stale row that compiles into a broken
     * query — which is exactly why columns are never copied into DB2.
     */
    @Test
    void listColumnsDropsCuratedColumnsThatNoLongerExistUpstream() {
        stubRegistered();
        stubLiveColumns("bank_id");
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(List.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, TABLE, "dropped_column"),
                        "Gone", false, true)));

        assertEquals(List.of("bank_id"), service.listColumns(CATALOG, SCHEMA, TABLE).stream()
                .map(LakehouseRegistryService.RegisteredColumn::name).toList());
    }

    /** Gate 1: live existence is not enough — an unregistered table is off limits. */
    @Test
    void validateColumnRejectsAnUnregisteredTableEvenThoughItExistsLive() {
        when(registrations.existsByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.validateColumn(new QualifiedColumn(CATALOG, SCHEMA, TABLE, "bank_id")));
        assertTrue(e.getMessage().contains("not registered"), e.getMessage());
        verify(browse, never()).listColumns(CATALOG, SCHEMA, TABLE);
    }

    /** Gate 2: registration is not enough — the column must exist live too. */
    @Test
    void validateColumnRejectsAColumnMissingFromTheLiveTable() {
        stubRegistered();
        stubLiveColumns("bank_id");
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.validateColumn(new QualifiedColumn(CATALOG, SCHEMA, TABLE, "ghost")));
    }

    @Test
    void validateColumnRejectsAHiddenColumn() {
        stubRegistered();
        stubLiveColumns("bank_id", "m_id");
        when(columnMetadata.findByCatalogNameAndSchemaNameAndTableName(CATALOG, SCHEMA, TABLE))
                .thenReturn(List.of(new AnalysisColumnMetadata(
                        1L, new QualifiedColumn(CATALOG, SCHEMA, TABLE, "m_id"),
                        null, false, false)));

        assertThrows(IllegalArgumentException.class,
                () -> service.validateColumn(new QualifiedColumn(CATALOG, SCHEMA, TABLE, "m_id")));
    }

    /** The officer-facing cascade reads the registry, never the live lakehouse. */
    @Test
    void officerFacingCatalogListComesFromTheRegistryNotTheCluster() {
        when(registrations.findDistinctCatalogNames()).thenReturn(List.of(CATALOG));

        assertEquals(List.of(CATALOG), service.listCatalogs());
        verify(browse, never()).listCatalogs();
    }
}
