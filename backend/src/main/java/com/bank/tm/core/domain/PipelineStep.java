package com.bank.tm.core.domain;

import java.time.Instant;
import java.util.Map;

public record PipelineStep(
        String reviewId,
        Stage stage,
        StepStatus status,
        String message,
        Map<String, Object> metrics,
        Instant startedAt,
        Instant finishedAt) {

    public PipelineStep {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static PipelineStep pending(String reviewId, Stage stage) {
        return new PipelineStep(reviewId, stage, StepStatus.PENDING, null, Map.of(), null, null);
    }
}
