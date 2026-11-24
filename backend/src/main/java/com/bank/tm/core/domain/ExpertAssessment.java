package com.bank.tm.core.domain;

import java.time.Instant;
import java.util.List;

/** Uzmanın kararı ve değiştirdiği değerler. null alan = sistem/AI değeri geçerli. */
public record ExpertAssessment(
        Decision decision,
        String title,
        String description,
        Integer likelihood,
        Integer impact,
        Integer score,
        String level,
        String rationale,
        List<Mitigation> mitigations,
        String note,
        String decidedBy,
        Instant decidedAt) {

    public static ExpertAssessment empty() {
        return new ExpertAssessment(Decision.PENDING, null, null, null, null, null, null, null, null, null, null,
                null);
    }
}
