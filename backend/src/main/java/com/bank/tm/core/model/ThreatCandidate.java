package com.bank.tm.core.model;

import java.util.List;

/** Kural motorunun deterministik olarak ürettiği aday tehdit. */
public record ThreatCandidate(
        String id,
        String ruleId,
        Stride stride,
        String targetId,
        String targetName,
        String title,
        String description,
        int defaultLikelihood,
        int defaultImpact,
        String ragQuery,
        List<String> tags) {

    public ThreatCandidate {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
