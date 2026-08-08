package gov.rajasthan.smart.srse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Verifies mock officer tokens issued by {@link MockJwtIssuer}. Active only
 * when srse.auth-mode=mock; invalid/missing tokens simply leave the security
 * context unauthenticated, so downstream hasAuthority(...) checks reject them
 * with the framework's normal 401/403 — this filter never itself denies.
 */
@Component
@ConditionalOnProperty(name = "srse.auth-mode", havingValue = "mock", matchIfMissing = true)
public class MockJwtAuthenticationFilter extends OncePerRequestFilter {

    private final MockJwtService jwtService;

    public MockJwtAuthenticationFilter(MockJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                List<String> authorities = jwtService.parseAuthorities(header.substring(7));
                List<GrantedAuthority> grantedAuthorities = authorities.stream()
                        .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                        .toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("mock-officer", null, grantedAuthorities));
            } catch (RuntimeException ex) {
                // Invalid/expired token — leave unauthenticated; downstream authorization denies it.
            }
        }
        filterChain.doFilter(request, response);
    }
}
