package gov.rajasthan.smart.srse.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisColumnMetadataRepository extends JpaRepository<AnalysisColumnMetadata, Long> {

    Optional<AnalysisColumnMetadata> findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
            String catalogName, String schemaName, String tableName, String columnName);

    /** All curated columns of one registered table — drives the officer-facing column list. */
    List<AnalysisColumnMetadata> findByCatalogNameAndSchemaNameAndTableName(
            String catalogName, String schemaName, String tableName);

    List<AnalysisColumnMetadata> findAllByOrderByCatalogNameAscSchemaNameAscTableNameAscColumnNameAsc();
}
