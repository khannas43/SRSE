package gov.rajasthan.smart.srse.security;

/**
 * Granted-authority constants used by {@code hasAuthority(...)}/{@code @PreAuthorize}
 * checks. RajSewadwar / Aadhaar OTP maps SSO roles onto these in
 * {@link RajSewadwarAuthenticationFilter} — not by editing {@code SecurityConfig}.
 */
public final class Authorities {

    /** Rule simulation, Analysis match, scheme authoring — officer screens. */
    public static final String STATE_OFFICER = "STATE_OFFICER";

    /** Lakehouse browse, registrations, field catalogue/mapping writes, analysis column overrides. */
    public static final String SRSE_ADMIN = "SRSE_ADMIN";

    private Authorities() {
    }
}
