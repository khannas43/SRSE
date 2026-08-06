package gov.rajasthan.smart.srse.scenario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    List<Scenario> findBySchemeIdOrderByCreatedAtDesc(String schemeId);
}
