package gov.rajasthan.smart.srse.security;

/**
 * Granted-authority constants used by {@code hasAuthority(...)}/{@code @PreAuthorize}
 * checks. STATE_OFFICER is the only role in scope until Arvind's RajSewadwar
 * RBAC spec lands (CLAUDE.md open items).
 */
public final class Authorities {

    public static final String STATE_OFFICER = "STATE_OFFICER";

    private Authorities() {
    }
}
