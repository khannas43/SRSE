package gov.rajasthan.smart.srse.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard: if broad {@code /api/analysis/**} is declared before the
 * narrow PUT/DELETE {@code /api/analysis/column-metadata} admin rules, officers
 * gain write access to column overrides while the narrow rules never run.
 */
class SecurityConfigMatcherOrderTest {

    private static final Path SECURITY_CONFIG = Path.of(
            "src/main/java/gov/rajasthan/smart/srse/config/SecurityConfig.java");

    @Test
    void schemeTemplateAdminMatcherPrecedesBroadSchemesPrefix() throws Exception {
        List<String> lines = Files.readAllLines(SECURITY_CONFIG);
        int putTemplate = indexOfFirstContaining(lines, "HttpMethod.PUT", "/api/schemes/*/template");
        int broadSchemes = indexOfFirstContaining(lines, "/api/schemes/**");
        assertTrue(putTemplate >= 0, "PUT /api/schemes/*/template matcher missing");
        assertTrue(broadSchemes >= 0, "Broad /api/schemes/** matcher missing");
        assertTrue(putTemplate < broadSchemes,
                "PUT scheme template must be declared before /api/schemes/**");
    }

    @Test
    void columnMetadataAdminMatchersPrecedeBroadAnalysisPrefix() throws Exception {
        List<String> lines = Files.readAllLines(SECURITY_CONFIG);
        int putColumnMetadata = indexOfFirstContaining(lines, "HttpMethod.PUT", "/api/analysis/column-metadata");
        int deleteColumnMetadata = indexOfFirstContaining(lines, "HttpMethod.DELETE", "/api/analysis/column-metadata");
        int broadAnalysis = indexOfFirstContaining(lines, "/api/analysis/**");

        assertTrue(putColumnMetadata >= 0, "PUT /api/analysis/column-metadata matcher missing");
        assertTrue(deleteColumnMetadata >= 0, "DELETE /api/analysis/column-metadata matcher missing");
        assertTrue(broadAnalysis >= 0, "Broad /api/analysis/** matcher missing");
        assertTrue(putColumnMetadata < broadAnalysis,
                "PUT column-metadata must be declared before /api/analysis/**");
        assertTrue(deleteColumnMetadata < broadAnalysis,
                "DELETE column-metadata must be declared before /api/analysis/**");
    }

    private static int indexOfFirstContaining(List<String> lines, String... fragments) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("//")) {
                continue;
            }
            boolean all = true;
            for (String f : fragments) {
                if (!line.contains(f)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return i;
            }
        }
        return -1;
    }
}
