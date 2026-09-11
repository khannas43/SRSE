package gov.rajasthan.smart.srse.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Export/import of the full admin456 configuration as a single JSON file —
 * connections, lakehouse registrations, field catalogue, mappings, analysis
 * column metadata, and schemes.
 */
@RestController
@RequestMapping("/api/admin/config")
public class AdminConfigController {

    private final AdminConfigService configService;

    public AdminConfigController(AdminConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/export")
    public ResponseEntity<AdminConfigBundle> exportJson() {
        AdminConfigBundle bundle = configService.export();
        String filename = "srse-admin-config-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(bundle);
    }

    @PostMapping("/import")
    public AdminConfigService.ImportResult importJson(
            @RequestBody AdminConfigBundle bundle,
            @RequestParam(defaultValue = "true") boolean testConnections) {
        return configService.importConfig(bundle, new AdminConfigService.ImportOptions(testConnections));
    }
}
