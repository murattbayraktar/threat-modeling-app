package com.bank.tm.core.service;

import com.bank.tm.core.domain.AiAssessment;
import com.bank.tm.core.domain.Decision;
import com.bank.tm.core.domain.ExpertAssessment;
import com.bank.tm.core.domain.LlmCall;
import com.bank.tm.core.domain.Mitigation;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.PipelineStep;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewDocument;
import com.bank.tm.core.domain.SourceRef;
import com.bank.tm.core.domain.SystemAssessment;
import com.bank.tm.core.domain.ThreatRecord;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.validation.ValidationIssue;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API'ye dönen görünüm nesneleri. */
public final class Views {

    private Views() {
    }

    public record StepView(String stage, String label, String actor, String status, String message,
                           Map<String, Object> metrics, Instant startedAt, Instant finishedAt) {
        static StepView of(PipelineStep s) {
            return new StepView(s.stage().name(), s.stage().label(), s.stage().actor().name(), s.status().name(),
                    s.message(), s.metrics(), s.startedAt(), s.finishedAt());
        }
    }

    public record DocumentView(String id, String documentKey, String fileName, String contentType, long sizeBytes,
                               String extractionMethod, int sections, int chars, List<String> warnings,
                               boolean hasClientText) {
        static DocumentView of(ReviewDocument d) {
            return new DocumentView(d.id(), d.documentKey(), d.fileName(), d.contentType(), d.sizeBytes(),
                    d.parsed() == null ? null : d.parsed().extractionMethod(),
                    d.parsed() == null ? 0 : d.parsed().sections().size(),
                    d.parsed() == null ? 0 : d.parsed().charCount(),
                    d.parsed() == null ? List.of() : d.parsed().warnings(),
                    d.clientText() != null && !d.clientText().isBlank());
        }
    }

    public record ReviewSummary(Review review, Map<String, Long> threatLevels, long threats, long pendingDecisions) {
    }

    public record ReviewDetail(Review review, List<StepView> steps, List<DocumentView> documents,
                               Integer modelVersion, boolean modelApproved, String pendingLlmCallId,
                               Map<String, Long> threatLevels, long threats, long pendingDecisions, boolean running) {
    }

    public record ModelVersionMeta(int version, String source, String createdBy, Instant createdAt, String approvedBy,
                                   Instant approvedAt, int issues) {
        static ModelVersionMeta of(ModelVersion v) {
            return new ModelVersionMeta(v.version(), v.source(), v.createdBy(), v.createdAt(), v.approvedBy(),
                    v.approvedAt(), v.issues().size());
        }
    }

    public record ModelView(ModelVersion current, List<ModelVersionMeta> versions, boolean editable) {
    }

    public record ThreatView(
            String id, String code, int modelVersion, String candidateId, String ruleId, Stride stride,
            String targetId, String targetName, boolean aiAdditional,
            String title, String description, Integer likelihood, Integer impact, Integer score, String level,
            List<Mitigation> mitigations, Decision decision,
            AiAssessment ai, SystemAssessment system, ExpertAssessment expert,
            List<SourceRef> sources, List<ValidationIssue> issues) {

        public static ThreatView of(ThreatRecord t) {
            return new ThreatView(t.id(), t.code(), t.modelVersion(), t.candidateId(), t.ruleId(), t.stride(),
                    t.targetId(), t.targetName(), t.aiAdditional(), t.finalTitle(), t.finalDescription(),
                    t.finalLikelihood(), t.finalImpact(), t.finalScore(), t.finalLevel(), t.finalMitigations(),
                    t.decision(), t.ai(), t.system(), t.expert(), t.sources(), t.issues());
        }
    }

    public record LlmTaskView(String callId, String reviewId, String stage, String callKey, int attempt,
                              String task, String promptVersion, String systemPrompt, String userPrompt,
                              JsonNode schema, double temperature) {
        public static LlmTaskView of(LlmCall c) {
            return new LlmTaskView(c.id(), c.reviewId(), c.stage().name(), c.callKey(), c.attempt(),
                    c.request().task().name(), c.request().promptVersion(), c.request().systemPrompt(),
                    c.request().userPrompt(), c.request().schema(), c.request().temperature());
        }
    }

    public record LlmCallView(String id, String stage, String callKey, int attempt, String executor, String model,
                              String status, String promptVersion, String systemPrompt, String userPrompt,
                              String rawOutput, List<String> errors, Long durationMs, Instant createdAt,
                              Instant completedAt) {
        public static LlmCallView of(LlmCall c) {
            return new LlmCallView(c.id(), c.stage().name(), c.callKey(), c.attempt(), c.executor(), c.model(),
                    c.status().name(), c.request().promptVersion(), c.request().systemPrompt(),
                    c.request().userPrompt(), c.rawOutput(), c.errors(), c.durationMs(), c.createdAt(),
                    c.completedAt());
        }
    }
}
