package com.bank.tm.core.risk;

import java.util.List;
import java.util.Map;

/** datasets/rules/risk-methodology.json */
public record RiskMethodology(
        String version,
        String description,
        Scale scale,
        String formula,
        String baseline,
        List<Level> levels,
        Map<String, Baseline> strideDefaults) {

    public record Scale(int min, int max) {
    }

    public record Level(String id, String label, int min, int max) {
    }

    public record Baseline(int likelihood, int impact) {
    }

    public int clamp(int v) {
        return Math.max(scale.min(), Math.min(scale.max(), v));
    }

    public int score(int likelihood, int impact) {
        if (!"LIKELIHOOD_X_IMPACT".equals(formula)) {
            throw new IllegalStateException("Desteklenmeyen formül: " + formula);
        }
        return likelihood * impact;
    }

    public Level levelFor(int score) {
        return levels.stream().filter(l -> score >= l.min() && score <= l.max()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Skor için seviye tanımlı değil: " + score));
    }
}
