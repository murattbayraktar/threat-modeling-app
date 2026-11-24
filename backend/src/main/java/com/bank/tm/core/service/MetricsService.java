package com.bank.tm.core.service;

import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.Decision;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewStatus;
import com.bank.tm.core.domain.ThreatRecord;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * İzleme metrikleri (model kalitesi): kabul/red/yanlış pozitif oranları, tekrar oranı, eksik STRIDE kategorileri,
 * AI önlemlerinin değiştirilmeden kabul oranı, kural şablonuyla tamamlanan aday sayısı.
 */
public class MetricsService {

    private final ReviewStore store;

    public MetricsService(ReviewStore store) {
        this.store = store;
    }

    public Map<String, Object> compute() {
        List<Review> reviews = store.listReviews();
        long completed = reviews.stream().filter(r -> r.status() == ReviewStatus.COMPLETED).count();
        int threats = 0, decided = 0, accepted = 0, rejected = 0, falsePositive = 0, aiAdditional = 0,
                aiAdditionalAccepted = 0, acceptedWithAiMitigations = 0, fromTemplate = 0, overrides = 0;
        long llmThreats = 0, duplicates = 0;
        double missingStrideSum = 0;
        int completedWithThreats = 0;
        for (Review r : reviews) {
            List<ThreatRecord> list = store.listThreats(r.id());
            Set<Stride> acceptedStride = EnumSet.noneOf(Stride.class);
            for (ThreatRecord t : list) {
                threats++;
                if (t.aiAdditional()) aiAdditional++;
                if (t.ai().fromRuleTemplate()) fromTemplate++;
                if (t.expert().likelihood() != null || t.expert().impact() != null) overrides++;
                Decision d = t.decision();
                if (d != Decision.PENDING) decided++;
                switch (d) {
                    case ACCEPTED -> {
                        accepted++;
                        acceptedStride.add(t.stride());
                        if (t.aiAdditional()) aiAdditionalAccepted++;
                        if (t.expert().mitigations() == null) acceptedWithAiMitigations++;
                    }
                    case REJECTED -> rejected++;
                    case FALSE_POSITIVE -> falsePositive++;
                    default -> { }
                }
            }
            if (r.status() == ReviewStatus.COMPLETED && !list.isEmpty()) {
                completedWithThreats++;
                missingStrideSum += Stride.values().length - acceptedStride.size();
            }
            String report = store.getArtifact(r.id(), ArtifactKind.THREAT_VALIDATION_REPORT).orElse(null);
            if (report != null) {
                JsonNode n = Json.tree(report);
                llmThreats += n.path("llmThreats").asLong();
                duplicates += n.path("duplicatesRemoved").asLong();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reviews", reviews.size());
        m.put("completedReviews", completed);
        m.put("threats", threats);
        m.put("decided", decided);
        m.put("acceptanceRate", ratio(accepted, decided));
        m.put("rejectionRate", ratio(rejected, decided));
        m.put("falsePositiveRate", ratio(falsePositive, decided));
        m.put("duplicateRate", ratio(duplicates, llmThreats));
        m.put("aiAdditionalThreats", aiAdditional);
        m.put("aiAdditionalAcceptanceRate", ratio(aiAdditionalAccepted, aiAdditional));
        m.put("mitigationAcceptedUnchangedRate", ratio(acceptedWithAiMitigations, accepted));
        m.put("candidatesFilledFromRules", fromTemplate);
        m.put("expertRiskOverrides", overrides);
        m.put("avgMissingStrideCategories", completedWithThreats == 0 ? null
                : Math.round(10.0 * missingStrideSum / completedWithThreats) / 10.0);
        return m;
    }

    private static Double ratio(long a, long b) {
        return b == 0 ? null : Math.round(1000.0 * a / b) / 1000.0;
    }
}
