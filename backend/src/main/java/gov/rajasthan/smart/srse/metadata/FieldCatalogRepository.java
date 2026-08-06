package gov.rajasthan.smart.srse.metadata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FieldCatalogRepository extends JpaRepository<FieldCatalogEntry, Long> {

    Optional<FieldCatalogEntry> findByFieldKeyAndActiveTrue(String fieldKey);
}
