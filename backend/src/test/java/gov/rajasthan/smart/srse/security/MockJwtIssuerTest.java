package gov.rajasthan.smart.srse.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockJwtIssuerTest {

    @Test
    void defaultLoginIssuesOfficerToken() {
        MockJwtService service = new MockJwtService();
        MockJwtIssuer issuer = new MockJwtIssuer(service);
        String token = issuer.mockLogin(null).token();
        assertEquals(List.of(Authorities.STATE_OFFICER), service.parseAuthorities(token));
    }

    @Test
    void adminRoleQueryIssuesBothAuthorities() {
        MockJwtService service = new MockJwtService();
        MockJwtIssuer adminIssuer = new MockJwtIssuer(service);
        String token = adminIssuer.mockLogin("admin").token();
        assertEquals(
                List.of(Authorities.SRSE_ADMIN, Authorities.STATE_OFFICER),
                service.parseAuthorities(token));
    }
}
