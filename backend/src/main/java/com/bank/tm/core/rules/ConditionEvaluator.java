package com.bank.tm.core.rules;

import java.util.Map;

public class ConditionEvaluator {

    private final OperatorRegistry operators;

    public ConditionEvaluator(OperatorRegistry operators) {
        this.operators = operators;
    }

    public boolean evaluate(Condition c, Map<String, Object> facts) {
        return switch (c) {
            case Condition.Always a -> true;
            case Condition.All all -> all.conditions().stream().allMatch(x -> evaluate(x, facts));
            case Condition.Any any -> any.conditions().stream().anyMatch(x -> evaluate(x, facts));
            case Condition.Not not -> !evaluate(not.condition(), facts);
            case Condition.Predicate p -> operators.get(p.op()).test(facts.get(p.fact()), p.value());
        };
    }
}
