package gov.rajasthan.smart.srse.config;

import java.util.List;

import gov.rajasthan.smart.srse.security.AuthMode;
import gov.rajasthan.smart.srse.security.Authorities;
import gov.rajasthan.smart.srse.security.MockJwtAuthenticationFilter;
import gov.rajasthan.smart.srse.security.RajSewadwarAuthenticationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * spring-boot-starter-security auto-locks every endpoint behind a login form
 * when no SecurityFilterChain is defined; this opens /api/health/**,
 * /api/auth/mock-login and springdoc, and gates /api/decision/**,
 * /api/schemes/**, /api/metadata/**, /api/admin/** and /api/analysis/**
 * behind STATE_OFFICER —
 * enforced by whichever of {@link MockJwtAuthenticationFilter}
 * / {@link RajSewadwarAuthenticationFilter} is active for {@code srse.auth-mode}.
 * Real RajSewadwar SSO payload parsing is still a stub — see that filter's
 * TODO.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectProvider<MockJwtAuthenticationFilter> mockJwtFilter;
    private final ObjectProvider<RajSewadwarAuthenticationFilter> rajSewadwarFilter;

    public SecurityConfig(@Value("${srse.auth-mode}") String authModeConfig,
                          ObjectProvider<MockJwtAuthenticationFilter> mockJwtFilter,
                          ObjectProvider<RajSewadwarAuthenticationFilter> rajSewadwarFilter) {
        // Fails fast on a typo'd SRSE_AUTH_MODE instead of silently running
        // with neither auth filter wired (see AuthMode).
        AuthMode.valueOf(authModeConfig.toUpperCase());
        this.mockJwtFilter = mockJwtFilter;
        this.rajSewadwarFilter = rajSewadwarFilter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/mock-login").permitAll()
                .requestMatchers("/api/decision/**", "/api/schemes/**", "/api/metadata/**", "/api/admin/**",
                        "/api/analysis/**")
                    .hasAuthority(Authorities.STATE_OFFICER)
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Without this, Spring Boot's internal error-dispatch to /error gets blocked by security too, masking the real HTTP status/error body behind a generic 403.
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            );

        // authMode selects which of these ObjectProviders actually has a bean
        // (each filter is @ConditionalOnProperty on the same srse.auth-mode
        // value) — at most one is non-null.
        MockJwtAuthenticationFilter mock = mockJwtFilter.getIfAvailable();
        if (mock != null) {
            http.addFilterBefore(mock, UsernamePasswordAuthenticationFilter.class);
        }
        RajSewadwarAuthenticationFilter rajSewadwar = rajSewadwarFilter.getIfAvailable();
        if (rajSewadwar != null) {
            http.addFilterBefore(rajSewadwar, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
