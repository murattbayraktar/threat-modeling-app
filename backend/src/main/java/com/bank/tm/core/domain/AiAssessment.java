package com.bank.tm.core.domain;

import com.bank.tm.core.model.Confidence;

import java.util.List;

/** LLM'in önerisi. Sistem skoruna girmez; ayrı saklanır. */
public record AiAssessment(
        String title,
        String description,
        String attackScenario,
        Integer suggestedLikelihood,
        Integer suggestedImpact,
        String riskRationale,
        List<Mitigation> mitigations,
        Confidence confidence,
        List<String> assumptions,
        boolean applicable,
        String notApplicableReason,
        String llmCallId,
        boolean fromRuleTemplate) {

    public AiAssessment {
        mitigations = mitigations == null ? List.of() : List.copyOf(mitigations);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }
}
