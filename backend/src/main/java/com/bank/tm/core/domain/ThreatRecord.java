package com.bank.tm.core.domain;

import com.bank.tm.core.model.Stride;
import com.bank.tm.core.validation.ValidationIssue;

import java.time.Instant;
import java.util.List;

/**
 * Bir tehdidin üç ayrı değer kümesi: AI tahmini (ai), sistem hesabı (system) ve uzman kararı (expert).
 * Nihai değerler expert alanı doluysa ondan, değilse sistem/AI değerlerinden okunur.
 */
public record ThreatRecord(
        String id,
        String reviewId,
        String code,
        int modelVersion,
        String candidateId,
        String ruleId,
        Stride stride,
        String targetId,
        String targetName,
        boolean aiAdditional,
        AiAssessment ai,
        SystemAssessment system,
        ExpertAssessment expert,
        List<SourceRef> sources,
        List<ValidationIssue> issues,
        Instant createdAt,
        Instant updatedAt) {

    public ThreatRecord {
        sources = sources == null ? List.of() : List.copyOf(sources);
        issues = issues == null ? List.of() : List.copyOf(issues);
        if (expert == null) expert = ExpertAssessment.empty();
    }

    public Decision decision() { return expert.decision(); }

    public String finalTitle() { return expert.title() != null ? expert.title() : ai.title(); }

    public String finalDescription() { return expert.description() != null ? expert.description() : ai.description(); }

    public Integer finalLikelihood() {
        return expert.likelihood() != null ? expert.likelihood() : system != null ? system.likelihood() : null;
    }

    public Integer finalImpact() {
        return expert.impact() != null ? expert.impact() : system != null ? system.impact() : null;
    }

    public Integer finalScore() {
        return expert.score() != null ? expert.score() : system != null ? system.score() : null;
    }

    public String finalLevel() {
        return expert.level() != null ? expert.level() : system != null ? system.level() : null;
    }

    public List<Mitigation> finalMitigations() {
        return expert.mitigations() != null ? expert.mitigations() : ai.mitigations();
    }

    public ThreatRecord withSystem(SystemAssessment s, Instant now) {
        return new ThreatRecord(id, reviewId, code, modelVersion, candidateId, ruleId, stride, targetId, targetName,
                aiAdditional, ai, s, expert, sources, issues, createdAt, now);
    }

    public ThreatRecord withExpert(ExpertAssessment e, Instant now) {
        return new ThreatRecord(id, reviewId, code, modelVersion, candidateId, ruleId, stride, targetId, targetName,
                aiAdditional, ai, system, e, sources, issues, createdAt, now);
    }
}
