package gov.rajasthan.smart.srse.analysis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Schema-driven Source/Target table+column dropdowns for the Analysis tab.
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisSchemaController {

    private final AnalysisSchemaService schemaService;

    public AnalysisSchemaController(AnalysisSchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping("/tables")
    public List<String> tables() {
        return schemaService.listTables();
    }

    @GetMapping("/tables/{table}/columns")
    public List<AnalysisSchemaService.ColumnInfo> columns(@PathVariable String table) {
        return schemaService.listColumns(table);
    }
}
