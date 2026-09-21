package gov.rajasthan.smart.srse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Pending (Arvind, CLAUDE.md open items): real RajSewadwar SSO/JWT payload parsing.
 * The payload shape is not yet known, so this deliberately authenticates no
 * one — fail-closed rather than inventing a format. Every request under
 * srse.auth-mode=rajsewadwar is rejected by the downstream hasAuthority(...)
 * check until this is implemented.
 */
@Component
@ConditionalOnProperty(name = "srse.auth-mode", havingValue = "rajsewadwar")
public class RajSewadwarAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RajSewadwarAuthenticationFilter.class);

    public RajSewadwarAuthenticationFilter() {
        log.warn("srse.auth-mode=rajsewadwar but real SSO payload parsing is not implemented yet "
                + "(pending Arvind, CLAUDE.md open items) — every request will be rejected.");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
    }

    /**
     * SSO ROLE MAPPING SEAM (Aadhaar OTP / RajSewadwar): when the payload shape
     * is known, map its role list to SRSE authorities here only — do not scatter
     * authority assignment across filters or {@code SecurityConfig}. Admin tokens
     * should grant both {@link Authorities#SRSE_ADMIN} and
     * {@link Authorities#STATE_OFFICER}.
     */
    @SuppressWarnings("unused")
    private static List<GrantedAuthority> grantedAuthoritiesFromSsoRoles(List<String> ssoRoles) {
        // Pending Arvind / project SSO team: parse RajSewadwar payload → role strings → authorities.
        return List.of();
    }
}
