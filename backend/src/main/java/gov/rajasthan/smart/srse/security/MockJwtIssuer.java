package gov.rajasthan.smart.srse.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock officer login for local dev — issues a STATE_OFFICER-scoped token with
 * no credential check, since RajSewadwar SSO (the real credential source) is
 * still pending (CLAUDE.md open items, owner Arvind). Active only when
 * srse.auth-mode=mock.
 */
@RestController
@ConditionalOnProperty(name = "srse.auth-mode", havingValue = "mock", matchIfMissing = true)
public class MockJwtIssuer {

    private final MockJwtService jwtService;

    public MockJwtIssuer(MockJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/api/auth/mock-login")
    public MockLoginResponse mockLogin() {
        return new MockLoginResponse(jwtService.issue("mock-officer"));
    }

    public record MockLoginResponse(String token) {
    }
}
