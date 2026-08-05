package gov.rajasthan.smart.srse.compiler;

import java.util.List;

/**
 * Output of the compiler: a parameterised WHERE-clause fragment plus the
 * ordered list of bound parameter values.
 *
 * The predicate is ALWAYS parameterised ('?' placeholders). Values are bound,
 * never inlined — this is the structural SQL-injection defence. Callers pass
 * {@code params} straight to JdbcTemplate in order.
 */
public record CompiledQuery(String predicateSql, List<Object> params) {
    public CompiledQuery {
        params = List.copyOf(params);
    }
}
