package com.bank.tm.core.rules;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** JSON koşulunu {@link Condition} ağacına çevirir ve operatörleri yükleme anında doğrular. */
public class ConditionParser {

    private final OperatorRegistry operators;

    public ConditionParser(OperatorRegistry operators) {
        this.operators = operators;
    }

    public Condition parse(JsonNode node, String context) {
        if (node == null || node.isNull()) return new Condition.Always();
        if (node.has("all")) return new Condition.All(list(node.get("all"), context));
        if (node.has("any")) return new Condition.Any(list(node.get("any"), context));
        if (node.has("not")) return new Condition.Not(parse(node.get("not"), context));
        if (node.has("fact")) {
            String op = node.path("op").asText("eq");
            Operator operator;
            try {
                operator = operators.get(op);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(context + ": " + e.getMessage());
            }
            if (operator.requiresValue() && !node.has("value")) {
                throw new IllegalArgumentException(context + ": '" + op + "' operatörü için value gerekli");
            }
            return new Condition.Predicate(node.get("fact").asText(), op, toJava(node.get("value")));
        }
        throw new IllegalArgumentException(context + ": koşul all/any/not/fact içermeli: " + node);
    }

    private List<Condition> list(JsonNode arr, String context) {
        List<Condition> out = new ArrayList<>();
        int i = 0;
        for (JsonNode n : arr) out.add(parse(n, context + "[" + i++ + "]"));
        return out;
    }

    static Object toJava(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isArray()) {
            List<Object> out = new ArrayList<>();
            v.forEach(x -> out.add(toJava(x)));
            return out;
        }
        return v.asText();
    }
}
