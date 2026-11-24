package com.bank.tm.core.domain;

import java.util.List;

/** Risk motorunun metodolojiye göre hesapladığı değer. */
public record SystemAssessment(
        int likelihood,
        int impact,
        int score,
        String level,
        String baselineSource,
        int baselineLikelihood,
        int baselineImpact,
        List<RiskTraceStep> trace,
        String methodologyVersion,
        String riskRulesVersion) {

    public SystemAssessment {
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
