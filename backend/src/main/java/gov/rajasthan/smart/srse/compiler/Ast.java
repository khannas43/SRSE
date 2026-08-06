package gov.rajasthan.smart.srse.compiler;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Rule Abstract Syntax Tree — engine-agnostic intermediate representation.
 *
 * A ruleset is a boolean tree: leaves are predicates, internal nodes are AND/OR.
 * The compiler walks this to emit parameterised Presto SQL. Because all
 * cross-table logic is pre-materialised (flat-catalogue principle), the tree
 * only ever contains single-table threshold predicates — no joins.
 *
 * Java 17 sealed hierarchy + records: immutable, exhaustively switchable.
 */
public final class Ast {

    private Ast() {}

    /** Boolean combinator. */
    public enum BoolOp { AND, OR }

    /** Supported predicate operators (see design doc §6.1.2). */
    public enum Operator {
        EQ, NE, LT, LTE, GT, GTE,
        IN, NOT_IN,
        BETWEEN,
        IS_TRUE, IS_FALSE,
        IS_NULL, NOT_NULL
    }

    /** A node is either a group (AND/OR of children) or a leaf predicate. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = GroupNode.class, name = "GROUP"),
            @JsonSubTypes.Type(value = PredicateNode.class, name = "PREDICATE")
    })
    public sealed interface Node permits GroupNode, PredicateNode {}

    /** AND / OR over child nodes. */
    public record GroupNode(BoolOp op, List<Node> children) implements Node {
        public GroupNode {
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("GroupNode requires >= 1 child");
            }
            children = List.copyOf(children);
        }
    }

    /**
     * A single condition: field + operator + value.
     * {@code fieldKey} is an ABSTRACT catalogue key (e.g. "annual_income_total"),
     * resolved to a physical column by the mapping service at compile time.
     * {@code value} may be a scalar, a two-element list (BETWEEN), or a list (IN).
     */
    public record PredicateNode(String fieldKey, Operator operator, Object value)
            implements Node {
        public PredicateNode {
            if (fieldKey == null || fieldKey.isBlank()) {
                throw new IllegalArgumentException("PredicateNode requires a fieldKey");
            }
            if (operator == null) {
                throw new IllegalArgumentException("PredicateNode requires an operator");
            }
        }

        /** Convenience for value-less unary operators (IS_TRUE, IS_FALSE, IS_NULL, NOT_NULL). */
        public PredicateNode(String fieldKey, Operator operator) {
            this(fieldKey, operator, null);
        }
    }

    /** Root wrapper for a full ruleset specification. */
    public record PredicateSpec(Node root) {
        public PredicateSpec {
            if (root == null) {
                throw new IllegalArgumentException("PredicateSpec requires a root node");
            }
        }
    }
}
