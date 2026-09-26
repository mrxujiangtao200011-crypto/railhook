package com.webhook.platform.api.service.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.dto.ConditionNode;
import com.webhook.platform.api.dto.ConditionNode.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTreeEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, JsonNode> fieldCache;

    @BeforeEach
    void setUp() {
        fieldCache = ConditionTreeEvaluator.newFieldCache();
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void noConditionsOrAnEmptyGroupMatchesEverything() throws Exception {
        assertThat(ConditionTreeEvaluator.evaluate(null, json("{}"), fieldCache)).isTrue();
        Group group = Group.builder().op(GroupOperator.AND).children(List.of()).build();
        assertThat(ConditionTreeEvaluator.evaluate(group, json("{}"), fieldCache)).isTrue();
    }

    static Stream<Arguments> predicates() {
        Predicate eqString = typed("type", PredicateOperator.EQ, "order.created", ValueType.STRING);
        Predicate eqNumber = typed("data.amount", PredicateOperator.EQ, 100, ValueType.NUMBER);
        Predicate neq = typed("status", PredicateOperator.NEQ, "cancelled", ValueType.STRING);
        Predicate between = typed("amount", PredicateOperator.BETWEEN, List.of(10, 100), ValueType.NUMBER);
        Predicate in = typed("currency", PredicateOperator.IN, List.of("USD", "EUR"), ValueType.ARRAY_STRING);
        Predicate notIn = typed("currency", PredicateOperator.NOT_IN, List.of("USD", "EUR"), ValueType.ARRAY_STRING);
        Predicate regex = typed("type", PredicateOperator.REGEX, "^order\\..*", ValueType.STRING);
        Predicate exists = Predicate.builder().field("data.email").operator(PredicateOperator.EXISTS).build();
        Predicate notExists = Predicate.builder().field("data.email").operator(PredicateOperator.NOT_EXISTS).build();
        Predicate isNull = Predicate.builder().field("data.ref").operator(PredicateOperator.IS_NULL).build();
        Predicate notNull = Predicate.builder().field("data.ref").operator(PredicateOperator.NOT_NULL).build();
        Predicate caseInsensitive = Predicate.builder()
                .field("status").operator(PredicateOperator.EQ).value("active").valueType(ValueType.STRING)
                .caseInsensitive(true).build();
        String email = "{\"email\":\"user@example.com\"}";
        return Stream.of(
                Arguments.of(eqString, "{\"type\":\"order.created\"}", true),
                Arguments.of(eqString, "{\"type\":\"other\"}", false),
                Arguments.of(eqNumber, "{\"data\":{\"amount\":100}}", true),
                Arguments.of(eqNumber, "{\"data\":{\"amount\":200}}", false),
                Arguments.of(neq, "{\"status\":\"active\"}", true),
                Arguments.of(neq, "{\"status\":\"cancelled\"}", false),
                Arguments.of(pred("amount", PredicateOperator.GT, 40), "{\"amount\":50}", true),
                Arguments.of(pred("amount", PredicateOperator.GT, 50), "{\"amount\":50}", false),
                Arguments.of(pred("amount", PredicateOperator.GTE, 50), "{\"amount\":50}", true),
                Arguments.of(pred("amount", PredicateOperator.LT, 60), "{\"amount\":50}", true),
                Arguments.of(pred("amount", PredicateOperator.LTE, 50), "{\"amount\":50}", true),
                Arguments.of(pred("amount", PredicateOperator.LTE, 49), "{\"amount\":50}", false),
                Arguments.of(between, "{\"amount\":50}", true),
                Arguments.of(between, "{\"amount\":10}", true),
                Arguments.of(between, "{\"amount\":100}", true),
                Arguments.of(between, "{\"amount\":101}", false),
                Arguments.of(pred("email", PredicateOperator.CONTAINS, "example"), email, true),
                Arguments.of(pred("email", PredicateOperator.STARTS_WITH, "user@"), email, true),
                Arguments.of(pred("email", PredicateOperator.ENDS_WITH, ".com"), email, true),
                Arguments.of(pred("email", PredicateOperator.NOT_CONTAINS, "foo"), email, true),
                Arguments.of(in, "{\"currency\":\"USD\"}", true),
                Arguments.of(in, "{\"currency\":\"GBP\"}", false),
                Arguments.of(notIn, "{\"currency\":\"GBP\"}", true),
                Arguments.of(regex, "{\"type\":\"order.created\"}", true),
                Arguments.of(regex, "{\"type\":\"payment.done\"}", false),
                Arguments.of(exists, "{\"data\":{\"email\":\"a@b.com\"}}", true),
                Arguments.of(exists, "{\"data\":{}}", false),
                Arguments.of(notExists, "{\"data\":{}}", true),
                Arguments.of(isNull, "{\"data\":{\"ref\":null}}", true),
                Arguments.of(isNull, "{\"data\":{}}", true),
                Arguments.of(notNull, "{\"data\":{\"ref\":\"abc\"}}", true),
                Arguments.of(notNull, "{\"data\":{\"ref\":null}}", false),
                Arguments.of(caseInsensitive, "{\"status\":\"ACTIVE\"}", true),
                Arguments.of(caseInsensitive, "{\"status\":\"Active\"}", true),
                Arguments.of(pred("nonexistent", PredicateOperator.EQ, "something"), "{\"other\":1}", false),
                Arguments.of(pred("items[0].name", PredicateOperator.EQ, "widget"),
                        "{\"items\":[{\"name\":\"widget\"},{\"name\":\"gadget\"}]}", true));
    }

    @ParameterizedTest
    @MethodSource("predicates")
    void predicate(Predicate predicate, String event, boolean matches) throws Exception {
        assertThat(ConditionTreeEvaluator.evaluate(predicate, json(event), fieldCache)).isEqualTo(matches);
    }

    @Nested
    class NestedGroupTests {

        @Test
        void nestedAndInsideOr() throws Exception {
            Group tree = Group.builder()
                    .op(GroupOperator.OR)
                    .children(List.of(
                            Group.builder()
                                    .op(GroupOperator.AND)
                                    .children(List.of(
                                            pred("type", PredicateOperator.EQ, "order.created"),
                                            pred("total", PredicateOperator.GT, 500)
                                    ))
                                    .build(),
                            pred("type", PredicateOperator.EQ, "refund")
                    ))
                    .build();

            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"total\":1000}"), fieldCache)).isTrue();

            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"refund\",\"total\":10}"), fieldCache)).isTrue();

            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"total\":200}"), fieldCache)).isFalse();

            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment.done\",\"total\":1000}"), fieldCache)).isFalse();
        }

        @Test
        void notInsideAnd() throws Exception {
            Group tree = Group.builder()
                    .op(GroupOperator.AND)
                    .children(List.of(
                            pred("type", PredicateOperator.STARTS_WITH, "order."),
                            Group.builder()
                                    .op(GroupOperator.NOT)
                                    .children(List.of(pred("region", PredicateOperator.EQ, "EU")))
                                    .build()
                    ))
                    .build();

            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"region\":\"US\"}"), fieldCache)).isTrue();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"order.created\",\"region\":\"EU\"}"), fieldCache)).isFalse();
            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment.done\",\"region\":\"US\"}"), fieldCache)).isFalse();
        }

        @Test
        void deeplyNested3Levels() throws Exception {
            Group tree = Group.builder()
                    .op(GroupOperator.OR)
                    .children(List.of(
                            Group.builder()
                                    .op(GroupOperator.AND)
                                    .children(List.of(
                                            pred("type", PredicateOperator.EQ, "payment"),
                                            pred("amount", PredicateOperator.GTE, 1000)
                                    ))
                                    .build(),
                            Group.builder()
                                    .op(GroupOperator.AND)
                                    .children(List.of(
                                            pred("type", PredicateOperator.EQ, "refund"),
                                            Group.builder()
                                                    .op(GroupOperator.NOT)
                                                    .children(List.of(pred("reason", PredicateOperator.EQ, "duplicate")))
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .build();

            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment\",\"amount\":1500,\"reason\":\"whatever\"}"), fieldCache)).isTrue();

            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"refund\",\"amount\":10,\"reason\":\"customer_request\"}"), fieldCache)).isTrue();

            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"refund\",\"amount\":10,\"reason\":\"duplicate\"}"), fieldCache)).isFalse();

            fieldCache.clear();
            assertThat(ConditionTreeEvaluator.evaluate(tree,
                    json("{\"type\":\"payment\",\"amount\":500,\"reason\":\"whatever\"}"), fieldCache)).isFalse();
        }
    }

    @Test
    void jsonRoundTrip_nestedNotGroup() throws Exception {
        String treeJson = """
                {
                  "type": "group",
                  "op": "OR",
                  "children": [
                    {
                      "type": "group",
                      "op": "NOT",
                      "children": [
                        { "type": "predicate", "field": "region", "operator": "EQ", "value": "EU", "valueType": "STRING" }
                      ]
                    },
                    { "type": "predicate", "field": "vip", "operator": "EQ", "value": true, "valueType": "BOOLEAN" }
                  ]
                }
                """;

        ConditionNode parsed = mapper.readValue(treeJson, ConditionNode.class);
        assertThat(parsed).isInstanceOf(Group.class);

        Group root = (Group) parsed;
        assertThat(root.getOp()).isEqualTo(GroupOperator.OR);
        assertThat(root.getChildren()).hasSize(2);
        assertThat(root.getChildren().get(0)).isInstanceOf(Group.class);
        assertThat(((Group) root.getChildren().get(0)).getOp()).isEqualTo(GroupOperator.NOT);
    }

    @Nested
    class SecurityRegressionTests {

        @Test
        void regex_tooLong_rejected() throws Exception {
            String longPattern = "a".repeat(300);
            Predicate p = Predicate.builder()
                    .field("name").operator(PredicateOperator.REGEX).value(longPattern).build();
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"name\":\"aaa\"}"), fieldCache)).isFalse();
        }

        @Test
        void regex_catastrophicBacktracking_timesOut() throws Exception {
            Predicate p = Predicate.builder()
                    .field("val").operator(PredicateOperator.REGEX).value("(a+)+$").build();
            long start = System.currentTimeMillis();
            boolean result = ConditionTreeEvaluator.evaluate(p,
                    json("{\"val\":\"" + "a".repeat(30) + "!\"}"), fieldCache);
            long elapsed = System.currentTimeMillis() - start;
            assertThat(result).isFalse();
            assertThat(elapsed).isLessThan(1000);
        }

        // "hello" GTE 42 once returned true: the parse failure became 0.
        @ParameterizedTest
        @EnumSource(value = PredicateOperator.class, names = {"GT", "GTE", "LT", "LTE"})
        void compareNumeric_nonNumericField_neverMatches(PredicateOperator op) throws Exception {
            Predicate p = pred("name", op, 42);
            assertThat(ConditionTreeEvaluator.evaluate(p, json("{\"name\":\"hello\"}"), fieldCache)).isFalse();
        }

    }

    private static Predicate pred(String field, PredicateOperator op, Object value) {
        return Predicate.builder().field(field).operator(op).value(value).build();
    }

    private static Predicate typed(String field, PredicateOperator op, Object value, ValueType type) {
        return Predicate.builder().field(field).operator(op).value(value).valueType(type).build();
    }
}
