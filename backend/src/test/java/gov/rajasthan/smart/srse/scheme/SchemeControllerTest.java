package gov.rajasthan.smart.srse.scheme;

import gov.rajasthan.smart.srse.security.MockJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pure controller-logic tests for the scheme registry. Security filters are
 * bypassed ({@code addFilters = false}) — RBAC is verified separately in
 * SecurityConfigTest's style for the decision seam.
 */
@WebMvcTest(SchemeController.class)
@AutoConfigureMockMvc(addFilters = false)
class SchemeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRepository repository;

    // MockJwtAuthenticationFilter is auto-detected by @WebMvcTest and needs this
    // to construct; addFilters = false means it's never actually invoked here.
    @MockBean
    private MockJwtService mockJwtService;

    @Test
    void listReturnsActiveSchemesOnly() throws Exception {
        Scheme scheme = new Scheme(1L, "EKAL_NAARI", "Ekal Naari Pension", "desc", true, Instant.now());
        when(repository.findByActiveTrueOrderByName()).thenReturn(List.of(scheme));

        mockMvc.perform(get("/api/schemes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("EKAL_NAARI"))
                .andExpect(jsonPath("$[0].name").value("Ekal Naari Pension"));
    }

    @Test
    void createPersistsAndReturnsNewScheme() throws Exception {
        when(repository.save(any(Scheme.class))).thenAnswer(inv -> {
            Scheme s = inv.getArgument(0);
            return new Scheme(7L, s.getCode(), s.getName(), s.getDescription(), s.isActive(), s.getCreatedAt());
        });

        String body = """
                {"code": "OLD_AGE_PENSION", "name": "Old Age Pension", "description": "60+ pension"}
                """;

        mockMvc.perform(post("/api/schemes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.code").value("OLD_AGE_PENSION"));
    }
}
