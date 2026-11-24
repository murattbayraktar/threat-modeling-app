package com.bank.tm.core;

import com.bank.tm.core.domain.SystemAssessment;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.risk.RiskEffect;
import com.bank.tm.core.risk.RiskEngine;
import com.bank.tm.core.risk.RiskRule;
import com.bank.tm.core.rules.Facts;
import com.bank.tm.core.rules.OperatorRegistry;
import com.bank.tm.core.rules.RuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskEngineTest {

    private final RuleRepository rules = new RuleRepository(Fixtures.datasets().resolve("rules"), new OperatorRegistry());

    @Test
    void sqlInjectionOnCustomerDatabaseIsCritical() {
        SystemModel model = RuleEngineTest.bankingModel();
        RiskEngine engine = new RiskEngine(rules, List.of());
        Map<String, Object> facts = Facts.forThreat(model, "F2", Stride.TAMPERING, "TR-F-T-02", false, null);
        SystemAssessment a = engine.assess(Stride.TAMPERING, 3, 5, facts);
        // Başlangıç 3x5 (kural varsayılanı); RR-04 sınır geçen + kimlik bilinmiyor => olasılık 4.
        // Tasarım dokümanındaki örnekle aynı sonuç: olasılık 4 x etki 5 = 20 (Kritik).
        assertEquals("THREAT_RULE", a.baselineSource());
        assertEquals(4, a.likelihood());
        assertEquals(5, a.impact());
        assertEquals(20, a.score());
        assertEquals("CRITICAL", a.level());
        assertTrue(a.trace().stream().anyMatch(t -> t.ruleId().equals("RR-04")));
    }

    @Test
    void strideDefaultsAreUsedWithoutRuleBaseline() {
        SystemModel model = RuleEngineTest.bankingModel();
        RiskEngine engine = new RiskEngine(rules, List.of());
        SystemAssessment a = engine.assess(Stride.REPUDIATION, null, null,
                Facts.forThreat(model, "C3", Stride.REPUDIATION, null, true, null));
        assertEquals("STRIDE_DEFAULT", a.baselineSource());
        assertEquals(2, a.baselineLikelihood());
    }

    @Test
    void codeRulesAreAppliedInOrder() {
        RiskRule capImpact = new RiskRule() {
            public String id() { return "JAVA-01"; }
            public int order() { return 1000; }
            public String description() { return "Test: etki en fazla 2"; }
            public Optional<RiskEffect> evaluate(Map<String, Object> facts) {
                return Optional.of(new RiskEffect(null, new RiskEffect.Adjustment("max", 2), "test"));
            }
        };
        SystemModel model = RuleEngineTest.bankingModel();
        SystemAssessment a = new RiskEngine(rules, List.of(capImpact)).assess(Stride.TAMPERING, 3, 5,
                Facts.forThreat(model, "F2", Stride.TAMPERING, "TR-F-T-02", false, null));
        assertEquals(2, a.impact());
        assertEquals("JAVA-01", a.trace().get(a.trace().size() - 1).ruleId());
    }
}
