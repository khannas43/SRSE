package gov.rajasthan.smart.srse.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalysisColumnMetadataRepository extends JpaRepository<AnalysisColumnMetadata, Long> {

    Optional<AnalysisColumnMetadata> findByTableNameAndColumnName(String tableName, String columnName);
}
