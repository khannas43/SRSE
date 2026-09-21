package gov.rajasthan.smart.srse.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Mock login for local dev — issues bearer tokens with no credential check.
 * Default is STATE_OFFICER only; {@code ?role=admin} adds SRSE_ADMIN as well
 * so admin456 can exercise the same authority seam RajSewadwar will use.
 * Active only when srse.auth-mode=mock (not production access control).
 */
@RestController
@ConditionalOnProperty(name = "srse.auth-mode", havingValue = "mock", matchIfMissing = true)
public class MockJwtIssuer {

    private final MockJwtService jwtService;

    public MockJwtIssuer(MockJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/api/auth/mock-login")
    public MockLoginResponse mockLogin(@RequestParam(required = false) String role) {
        if (role != null && role.equalsIgnoreCase("admin")) {
            return new MockLoginResponse(jwtService.issue(
                    "mock-admin",
                    List.of(Authorities.SRSE_ADMIN, Authorities.STATE_OFFICER)));
        }
        return new MockLoginResponse(jwtService.issue("mock-officer"));
    }

    public record MockLoginResponse(String token) {
    }
}
