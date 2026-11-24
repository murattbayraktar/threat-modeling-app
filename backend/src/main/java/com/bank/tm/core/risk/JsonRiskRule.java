package com.bank.tm.core.risk;

import com.bank.tm.core.rules.Condition;
import com.bank.tm.core.rules.ConditionEvaluator;

import java.util.Map;
import java.util.Optional;

public record JsonRiskRule(String id, int order, boolean enabled, String description, String rationale,
                           Condition when, RiskEffect.Adjustment likelihood, RiskEffect.Adjustment impact,
                           ConditionEvaluator evaluator) implements RiskRule {

    @Override
    public Optional<RiskEffect> evaluate(Map<String, Object> facts) {
        if (!enabled || !evaluator.evaluate(when, facts)) return Optional.empty();
        return Optional.of(new RiskEffect(likelihood, impact, rationale));
    }
}
