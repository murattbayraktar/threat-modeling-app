package com.bank.tm.core.service;

import com.bank.tm.core.domain.AuditEvent;
import com.bank.tm.core.domain.Decision;
import com.bank.tm.core.domain.LlmCall;
import com.bank.tm.core.domain.LlmCallStatus;
import com.bank.tm.core.domain.Mitigation;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ThreatRecord;
import com.bank.tm.core.model.MissingInformation;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.rules.RuleRepository;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.util.Clock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rapor verisi tamamen backend'de, kayıtlı tehdit ve kararlardan üretilir (LLM kullanılmaz). */
public class ReportBuilder {

    public record ThreatLine(String code, String title, String stride, String target, Integer likelihood,
                             Integer impact, Integer score, String level, String decision, boolean expertOverride) {
    }

    public record ActionItem(String threatCode, String threatTitle, String level, String mitigationTitle,
                             String mitigationDescription, List<String> sourceIds, String origin) {
    }

    public record Versions(String threatRules, String riskRules, String methodology, Set<String> prompts,
                           Set<String> llmExecutors, Integer modelVersion, String modelApprovedBy) {
    }

    public record Report(Review review, Instant generatedAt, Map<String, Integer> modelSummary,
                         Map<String, Integer> decisions, Map<String, Integer> strideDistribution,
                         Map<String, Integer> levelDistribution, int[][] riskMatrix, List<ThreatLine> criticalThreats,
                         List<ThreatLine> threats, List<ActionItem> actionPlan, List<String> missingStrideCategories,
                         List<MissingInformation> openQuestions, Versions versions, List<AuditEvent> audit,
                         String disclaimer) {
    }

    private final ReviewStore store;
    private final RuleRepository rules;

    public ReportBuilder(ReviewStore store, RuleRepository rules) {
        this.store = store;
        this.rules = rules;
    }

    public Report build(String reviewId) {
        Review review = store.findReview(reviewId).orElseThrow(() -> new NotFoundException("İnceleme yok: " + reviewId));
        List<ThreatRecord> threats = store.listThreats(reviewId);
        ModelVersion model = store.approvedModel(reviewId).orElse(store.latestModel(reviewId).orElse(null));

        Map<String, Integer> modelSummary = new LinkedHashMap<>();
        if (model != null) {
            modelSummary.put("components", model.model().components().size());
            modelSummary.put("dataFlows", model.model().dataFlows().size());
            modelSummary.put("trustBoundaries", model.model().trustBoundaries().size());
        }
        Map<String, Integer> decisions = new LinkedHashMap<>();
        for (Decision d : Decision.values()) decisions.put(d.name(), 0);
        threats.forEach(t -> decisions.merge(t.decision().name(), 1, Integer::sum));

        List<ThreatRecord> active = threats.stream()
                .filter(t -> t.decision() != Decision.REJECTED && t.decision() != Decision.FALSE_POSITIVE
                        && t.ai().applicable())
                .toList();
        Map<String, Integer> stride = new LinkedHashMap<>();
        for (Stride s : Stride.values()) stride.put(s.name(), 0);
        active.forEach(t -> stride.merge(t.stride().name(), 1, Integer::sum));
        Map<String, Integer> levels = new LinkedHashMap<>();
        rules.methodology().levels().forEach(l -> levels.put(l.id(), 0));
        int[][] matrix = new int[5][5]; // [olasılık-1][etki-1]
        for (ThreatRecord t : active) {
            if (t.finalLevel() != null) levels.merge(t.finalLevel(), 1, Integer::sum);
            if (t.finalLikelihood() != null && t.finalImpact() != null) {
                matrix[t.finalLikelihood() - 1][t.finalImpact() - 1]++;
            }
        }
        List<ThreatLine> lines = active.stream()
                .sorted(Comparator.comparing((ThreatRecord t) -> t.finalScore() == null ? 0 : t.finalScore()).reversed())
                .map(ReportBuilder::line).toList();
        List<ThreatLine> critical = lines.stream().filter(l -> "CRITICAL".equals(l.level()) || "HIGH".equals(l.level())).toList();

        List<ActionItem> actions = new ArrayList<>();
        threats.stream().filter(t -> t.decision() == Decision.ACCEPTED)
                .sorted(Comparator.comparing((ThreatRecord t) -> t.finalScore() == null ? 0 : t.finalScore()).reversed())
                .forEach(t -> {
                    for (Mitigation m : t.finalMitigations()) {
                        actions.add(new ActionItem(t.code(), t.finalTitle(), t.finalLevel(), m.title(), m.description(),
                                m.sourceIds(), m.origin()));
                    }
                });
        List<String> missingStride = stride.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList();

        Set<String> prompts = new LinkedHashSet<>();
        Set<String> executors = new LinkedHashSet<>();
        for (LlmCall c : store.listLlmCalls(reviewId)) {
            if (c.status() == LlmCallStatus.COMPLETED) {
                prompts.add(c.request().promptVersion());
                executors.add(c.executor() + (c.model() == null ? "" : " / " + c.model()));
            }
        }
        Versions versions = new Versions(rules.threatRulesVersion(), rules.riskRulesVersion(),
                rules.methodology().version(), prompts, executors, model == null ? null : model.version(),
                model == null ? null : model.approvedBy());
        List<AuditEvent> audit = store.listAudit(reviewId);
        return new Report(review, Clock.now(), modelSummary, decisions, stride, levels, matrix, critical, lines, actions,
                missingStride, model == null ? List.of() : model.model().missingInformation(), versions, audit,
                "Bu tehditler mimari dokümanlardan türetilmiş olasılıklardır; doğrulanmış güvenlik açıkları değildir. "
                        + "Risk seviyeleri gerçek sistem testi yapılmadan kesin kabul edilmemelidir.");
    }

    private static ThreatLine line(ThreatRecord t) {
        boolean override = t.expert().likelihood() != null || t.expert().impact() != null;
        return new ThreatLine(t.code(), t.finalTitle(), t.stride().name(), t.targetName(), t.finalLikelihood(),
                t.finalImpact(), t.finalScore(), t.finalLevel(), t.decision().name(), override);
    }
}
