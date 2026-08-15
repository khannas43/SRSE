package gov.rajasthan.smart.srse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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
}
