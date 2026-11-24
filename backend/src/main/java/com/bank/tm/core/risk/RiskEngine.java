package com.bank.tm.core.risk;

import com.bank.tm.core.domain.RiskTraceStep;
import com.bank.tm.core.domain.SystemAssessment;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.rules.RuleRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Metodolojiye dayalı risk hesabı:
 * 1) Başlangıç: aday tehdidi üreten kuralın varsayılanları, yoksa STRIDE varsayılanları.
 * 2) Risk kuralları sırayla (order) uygulanır; her ayarlama izlenebilir şekilde kaydedilir.
 * 3) Skor = olasılık × etki, seviye metodoloji eşiklerinden okunur.
 * LLM'in önerdiği değerler bu hesaba girmez.
 */
public class RiskEngine {

    private final RuleRepository rules;
    private final List<RiskRule> codeRules;

    public RiskEngine(RuleRepository rules, List<RiskRule> codeRules) {
        this.rules = rules;
        this.codeRules = List.copyOf(codeRules);
    }

    public SystemAssessment assess(Stride stride, Integer ruleLikelihood, Integer ruleImpact,
                                   Map<String, Object> facts) {
        RiskMethodology m = rules.methodology();
        String baselineSource;
        int l;
        int i;
        if (ruleLikelihood != null && ruleImpact != null) {
            l = ruleLikelihood;
            i = ruleImpact;
            baselineSource = "THREAT_RULE";
        } else {
            RiskMethodology.Baseline b = m.strideDefaults().get(stride.name());
            l = b.likelihood();
            i = b.impact();
            baselineSource = "STRIDE_DEFAULT";
        }
        int baseL = l;
        int baseI = i;
        List<RiskTraceStep> trace = new ArrayList<>();
        for (RiskRule rule : allRules()) {
            Optional<RiskEffect> effect = rule.evaluate(facts);
            if (effect.isEmpty()) continue;
            RiskEffect e = effect.get();
            if (e.likelihood() != null) {
                int after = m.clamp(e.likelihood().apply(l));
                trace.add(new RiskTraceStep(rule.id(), rule.description(), "likelihood", e.likelihood().op(),
                        e.likelihood().value(), l, after, e.rationale()));
                l = after;
            }
            if (e.impact() != null) {
                int after = m.clamp(e.impact().apply(i));
                trace.add(new RiskTraceStep(rule.id(), rule.description(), "impact", e.impact().op(),
                        e.impact().value(), i, after, e.rationale()));
                i = after;
            }
        }
        int score = m.score(l, i);
        return new SystemAssessment(l, i, score, m.levelFor(score).id(), baselineSource, baseL, baseI, trace,
                m.version(), rules.riskRulesVersion());
    }

    /** Uzmanın girdiği olasılık/etki için skor ve seviye (aynı metodoloji). */
    public int score(int likelihood, int impact) {
        RiskMethodology m = rules.methodology();
        return m.score(m.clamp(likelihood), m.clamp(impact));
    }

    public String level(int score) {
        return rules.methodology().levelFor(score).id();
    }

    private List<RiskRule> allRules() {
        List<RiskRule> all = new ArrayList<>(rules.riskRules());
        all.addAll(codeRules);
        all.sort(Comparator.comparingInt(RiskRule::order));
        return all;
    }
}
