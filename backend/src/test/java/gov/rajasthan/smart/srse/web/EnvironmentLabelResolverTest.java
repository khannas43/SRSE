package gov.rajasthan.smart.srse.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvironmentLabelResolverTest {

    @Test
    void derivesDevelopmentForSyntheticMode() {
        assertEquals("Development", EnvironmentLabelResolver.resolve("", "synthetic"));
    }

    @Test
    void derivesProductionLiveForLiveMode() {
        assertEquals("Production (Live)", EnvironmentLabelResolver.resolve(null, "live"));
    }

    @Test
    void configuredLabelOverridesDerivedName() {
        assertEquals("UAT", EnvironmentLabelResolver.resolve("UAT", "live"));
    }
}
