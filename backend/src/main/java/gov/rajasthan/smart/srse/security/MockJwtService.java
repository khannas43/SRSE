package gov.rajasthan.smart.srse.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Issues and verifies short-lived mock officer tokens for local dev
 * (srse.auth-mode=mock, the default). The signing key is generated fresh per
 * boot — acceptable here since mock mode never runs against real client data;
 * tokens simply stop validating across restarts. Real RajSewadwar SSO/JWT
 * verification is a separate, unimplemented seam — see
 * {@link RajSewadwarAuthenticationFilter}.
 */
@Component
@ConditionalOnProperty(name = "srse.auth-mode", havingValue = "mock", matchIfMissing = true)
public class MockJwtService {

    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    private final SecretKey signingKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);

    public String issue(String subject) {
        Instant now = Instant.now();
        Instant expires = now.plus(TOKEN_TTL);
        return Jwts.builder()
                .setSubject(subject)
                .claim("authorities", List.of(Authorities.STATE_OFFICER))
                .setIssuedAt(toLegacyDate(now))
                .setExpiration(toLegacyDate(expires))
                .signWith(signingKey)
                .compact();
    }

    /** jjwt 0.11.x API requires {@link java.util.Date}; isolated here for Sonar S2143. */
    @SuppressWarnings("java:S2143")
    private static java.util.Date toLegacyDate(Instant instant) {
        return java.util.Date.from(instant);
    }

    @SuppressWarnings("unchecked")
    public List<String> parseAuthorities(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return (List<String>) claims.get("authorities", List.class);
    }
}
