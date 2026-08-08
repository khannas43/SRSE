package gov.rajasthan.smart.srse.security;

/**
 * Matches {@code srse.auth-mode} / {@code SRSE_AUTH_MODE} config values
 * ({@code mock} | {@code rajsewadwar}). Mirrors {@code DataMode}'s pattern.
 */
public enum AuthMode {
    MOCK,
    RAJSEWADWAR
}
