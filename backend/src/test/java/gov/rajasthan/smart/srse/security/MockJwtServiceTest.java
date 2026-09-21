package gov.rajasthan.smart.srse.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockJwtServiceTest {

    private final MockJwtService jwtService = new MockJwtService();

    @Test
    void defaultIssueCarriesOfficerAuthorityOnly() {
        String token = jwtService.issue("officer-subject");
        assertEquals(List.of(Authorities.STATE_OFFICER), jwtService.parseAuthorities(token));
    }

    @Test
    void issueWithExplicitAuthoritiesRoundTrips() {
        List<String> authorities = List.of(Authorities.SRSE_ADMIN, Authorities.STATE_OFFICER);
        String token = jwtService.issue("admin-subject", authorities);
        assertEquals(authorities, jwtService.parseAuthorities(token));
    }

    @Test
    void parseAuthoritiesReturnsCopyNotMutatingClaim() {
        String token = jwtService.issue("x", List.of(Authorities.STATE_OFFICER));
        List<String> parsed = jwtService.parseAuthorities(token);
        assertTrue(parsed.contains(Authorities.STATE_OFFICER));
    }
}
