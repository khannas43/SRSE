package gov.rajasthan.smart.srse.analysis;

import java.util.List;
import java.util.Map;

/**
 * {@code sql} is the actual compiled statement rendered with literal values
 * in place of {@code ?} placeholders, for officer-facing display only — it
 * is not re-executed from this string. {@code capped} is true when the
 * result hit {@code analysisRowCap} and may not be the complete match set.
 */
public record RecordMatchResponse(
        List<String> columns,
        List<Map<String, Object>> rows,
        String sql,
        boolean capped) {
}
