package com.bank.tm.core.rules;

import com.bank.tm.core.domain.Review;
import com.bank.tm.core.model.Component;
import com.bank.tm.core.model.DataFlow;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.model.ThreatCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * STRIDE-per-element kurallarını sistem modelinin her bileşen ve akışına uygular. Deterministiktir: aynı model
 * ve aynı kural seti her zaman aynı adayları üretir.
 */
public class ThreatCandidateEngine {

    private final RuleRepository rules;
    private final ConditionEvaluator evaluator;

    public ThreatCandidateEngine(RuleRepository rules, ConditionEvaluator evaluator) {
        this.rules = rules;
        this.evaluator = evaluator;
    }

    public List<ThreatCandidate> generate(SystemModel model, Review review) {
        List<ThreatCandidate> out = new ArrayList<>();
        for (ThreatRule rule : rules.threatRules()) {
            if (!rule.enabled()) continue;
            if (rule.appliesTo() == ThreatRule.Target.COMPONENT) {
                for (Component c : model.components()) {
                    Map<String, Object> facts = Facts.forComponent(model, c, review);
                    if (evaluator.evaluate(rule.when(), facts)) out.add(candidate(rule, c.id(), c.name(), facts));
                }
            } else {
                for (DataFlow f : model.dataFlows()) {
                    Map<String, Object> facts = Facts.forFlow(model, f, review);
                    if (evaluator.evaluate(rule.when(), facts)) {
                        out.add(candidate(rule, f.id(), model.elementName(f.id()), facts));
                    }
                }
            }
        }
        return out;
    }

    private static ThreatCandidate candidate(ThreatRule rule, String targetId, String targetName,
                                             Map<String, Object> facts) {
        return new ThreatCandidate(rule.id() + ":" + targetId, rule.id(), rule.stride(), targetId, targetName,
                Templates.render(rule.titleTemplate(), facts), Templates.render(rule.descriptionTemplate(), facts),
                rule.defaultLikelihood(), rule.defaultImpact(), rule.ragQuery(), rule.tags());
    }
}
