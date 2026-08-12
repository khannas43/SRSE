package gov.rajasthan.smart.srse.scenario;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    // No DISTINCT: schemeIds is a Set, so a scheme id can join at most once per
    // scenario — and DB2 rejects SELECT DISTINCT over a Scenario column list
    // that includes LOB columns (ruleset_json / breakdown_json) anyway.
    @Query("SELECT s FROM Scenario s JOIN s.schemeIds sid WHERE sid = :schemeId ORDER BY s.createdAt DESC")
    List<Scenario> findBySchemeId(@Param("schemeId") Long schemeId);
}
