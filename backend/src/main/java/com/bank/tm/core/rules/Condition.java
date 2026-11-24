package com.bank.tm.core.rules;

import java.util.List;

/**
 * Kural koşulu ağacı. JSON karşılığı:
 * <pre>
 * {"all": [ ... ]}  {"any": [ ... ]}  {"not": { ... }}
 * {"fact": "element.zone", "op": "eq", "value": "INTERNET"}
 * </pre>
 */
public sealed interface Condition {

    record All(List<Condition> conditions) implements Condition {
    }

    record Any(List<Condition> conditions) implements Condition {
    }

    record Not(Condition condition) implements Condition {
    }

    record Predicate(String fact, String op, Object value) implements Condition {
    }

    /** Her zaman doğru (koşulsuz kural). */
    record Always() implements Condition {
    }
}
