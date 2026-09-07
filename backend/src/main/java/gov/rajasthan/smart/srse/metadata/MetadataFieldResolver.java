package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.FieldResolver;
import gov.rajasthan.smart.srse.compiler.FieldResolver.UnconfiguredFieldException;
import gov.rajasthan.smart.srse.compiler.SqlTypeFamily;
import gov.rajasthan.smart.srse.compiler.TypeCoercion;
import gov.rajasthan.smart.srse.lakehouse.LakehouseBrowseService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link FieldResolver} — the only one, for every DATA_MODE.
 *
 * <p>It used to cover LIVE only, with a hardcoded {@code StubFieldResolver}
 * standing in for SYNTHETIC. That made the Admin page lie in synthetic mode:
 * the mapping editor wrote to {@link FieldColumnMapping} and the engine read a
 * Java {@code Map}, so an admin could edit and save a synthetic binding and
 * nothing whatsoever would change — and a field added through "Add field"
 * could not resolve at all, because the stub had never heard of it. Both modes
 * now read the same table, keyed by {@link DataMode}, which is what the stub's
 * own javadoc had listed as the build task.
 *
 * <p>The synthetic bindings live in {@code field-catalog-seed.yml} and are
 * inserted at boot by {@link FieldCatalogSeedRunner}, so a fresh database comes
 * up with exactly the bindings the stub used to hardcode.
 *
 * CONTRACT (do not violate):
 *  - Allow-list gate: only catalogued + active + mapped-for-this-environment
 *    field keys resolve; anything else throws {@link UnknownFieldException}.
 *  - A field whose mapping is still the shipped CHANGE_ME placeholder throws
 *    {@link UnconfiguredFieldException} rather than resolving, so an
 *    unconfigured environment fails with an actionable message instead of
 *    emitting the placeholder into SQL as a table name.
 *  - Physical expressions come from {@link FieldColumnMapping} for the configured
 *    {@link DataMode} — never from officer input as SQL identifiers.
 *  - {@link #resolveColumn} may wrap the binding in a CAST so the column can be
 *    compared against the officer's value at all; {@link #resolveRawColumn}
 *    never does. See {@link #coerceToFieldType}.
 *  - Results are Caffeine-cached ({@code fieldMappings}) since lookups happen on
 *    every rule compile (CLAUDE.md: Metadata / mapping service, JPA + Caffeine).
 */
@Component
public class MetadataFieldResolver implements FieldResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataFieldResolver.class);

    private final FieldCatalogRepository catalogRepository;
    private final FieldColumnMappingRepository mappingRepository;
    private final LakehouseBrowseService browse;
    private final DataMode dataMode;

    public MetadataFieldResolver(FieldCatalogRepository catalogRepository,
                                 FieldColumnMappingRepository mappingRepository,
                                 LakehouseBrowseService browse,
                                 @Value("${srse.data-mode}") String dataModeConfig) {
        this.catalogRepository = catalogRepository;
        this.mappingRepository = mappingRepository;
        this.browse = browse;
        this.dataMode = DataMode.valueOf(dataModeConfig.toUpperCase());
    }

    @Override
    @Cacheable(cacheNames = "fieldMappings", key = "#fieldKey")
    public String resolveColumn(String fieldKey) {
        FieldCatalogEntry entry = catalogEntry(fieldKey);
        return coerceToFieldType(entry, physicalExpression(entry));
    }

    @Override
    @Cacheable(cacheNames = "fieldMappings", key = "'raw:' + #fieldKey")
    public String resolveRawColumn(String fieldKey) {
        return physicalExpression(catalogEntry(fieldKey));
    }

    private FieldCatalogEntry catalogEntry(String fieldKey) {
        return catalogRepository.findByFieldKeyAndActiveTrue(fieldKey)
                .orElseThrow(() -> new UnknownFieldException(fieldKey));
    }

    private String physicalExpression(FieldCatalogEntry entry) {
        // entry present and active — look up environment-specific binding
        String physicalExpression = mappingRepository
                .findByFieldKeyAndDataMode(entry.getFieldKey(), dataMode)
                .map(FieldColumnMapping::getPhysicalExpression)
                .orElseThrow(() -> new UnknownFieldException(entry.getFieldKey()));

        // Stop the shipped CHANGE_ME placeholder before it reaches SQL. Without
        // this the query ran against a table literally named CHANGE_ME and the
        // officer got a "table does not exist" error naming an object nobody
        // had configured — see UnconfiguredFieldException.
        if (FieldColumnMapping.isPlaceholder(physicalExpression)) {
            throw new UnconfiguredFieldException(entry.getFieldKey(), physicalExpression);
        }
        return physicalExpression;
    }

    /**
     * Casts the column, where needed, so it can be compared against the value
     * the officer will supply for this field.
     *
     * <p>The catalogue declares what a field IS — {@code annual_income} is a
     * NUMBER — while the lakehouse decides how it is STORED, and the two
     * disagree often enough to matter: an income or an account number held as
     * {@code varchar} in the golden layer made every rule using it fail to
     * compile, with {@code '<' cannot be applied to varchar, integer}. The
     * officer's value is bound over JDBC as a number, so the column is the side
     * that has to move (see {@link TypeCoercion#alignColumnToValue}).
     *
     * <p>Only a plain {@code catalog.schema.table.column} binding is coerced.
     * A Tier-2 expression ({@code date_diff('year', dob, current_date)}) is
     * already typed by the function that produced it, and there is no single
     * column to introspect.
     *
     * <p>Introspection failure is not fatal: if the live schema cannot be read,
     * the binding is emitted exactly as it is today and the query fails — or
     * succeeds — on its own terms. A metadata lookup must not be the thing that
     * takes a rule preview down.
     */
    private String coerceToFieldType(FieldCatalogEntry entry, String physicalExpression) {
        SqlTypeFamily valueType = valueFamily(entry.getDataType());
        if (valueType == SqlTypeFamily.UNKNOWN) {
            return physicalExpression;
        }
        SqlTypeFamily columnType = physicalFamily(physicalExpression);
        return TypeCoercion.alignColumnToValue(physicalExpression, columnType, valueType);
    }

    /** The family the officer's value for a field of this type will arrive as. */
    private static SqlTypeFamily valueFamily(FieldDataType dataType) {
        if (dataType == null) {
            return SqlTypeFamily.UNKNOWN;
        }
        return switch (dataType) {
            case NUMBER -> SqlTypeFamily.NUMBER;
            case STRING -> SqlTypeFamily.TEXT;
            case BOOLEAN -> SqlTypeFamily.BOOLEAN;
            case DATE -> SqlTypeFamily.TEMPORAL;
        };
    }

    /** Live type of a plain four-part binding; UNKNOWN for anything else. */
    private SqlTypeFamily physicalFamily(String physicalExpression) {
        QualifiedColumn column = parseQualifiedColumn(physicalExpression);
        if (column == null) {
            return SqlTypeFamily.UNKNOWN;
        }
        try {
            return browse.listColumns(column.table()).stream()
                    .filter(c -> c.name().equalsIgnoreCase(column.column()))
                    .findFirst()
                    .map(c -> SqlTypeFamily.of(c.dataType()))
                    .orElse(SqlTypeFamily.UNKNOWN);
        } catch (RuntimeException e) {
            log.debug("Could not read the live type of {} — emitting it uncast", physicalExpression, e);
            return SqlTypeFamily.UNKNOWN;
        }
    }

    /**
     * Parses {@code catalog.schema.table.column} into an address, or returns
     * null when the binding is anything else — an expression, or a binding
     * still missing its catalog/schema (which resolves against the JDBC URL's
     * default and cannot be introspected from here).
     */
    private static QualifiedColumn parseQualifiedColumn(String expression) {
        String trimmed = expression.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        for (String part : parts) {
            if (part.isEmpty() || !part.chars().allMatch(MetadataFieldResolver::isIdentifierChar)) {
                return null;
            }
        }
        return new QualifiedColumn(parts[0], parts[1], parts[2], parts[3]);
    }

    private static boolean isIdentifierChar(int c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
