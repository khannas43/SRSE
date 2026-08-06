package gov.rajasthan.smart.srse.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FieldColumnMappingRepository extends JpaRepository<FieldColumnMapping, Long> {

    Optional<FieldColumnMapping> findByFieldKeyAndDataMode(String fieldKey, DataMode dataMode);
}
