package gov.rajasthan.smart.srse.scenario;

public class ScenarioNotFoundException extends RuntimeException {

    public ScenarioNotFoundException(Long scenarioId) {
        super("Scenario not found: " + scenarioId);
    }
}
