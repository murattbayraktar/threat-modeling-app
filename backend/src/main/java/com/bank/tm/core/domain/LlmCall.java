package com.bank.tm.core.domain;

import com.bank.tm.core.llm.LlmRequest;

import java.time.Instant;
import java.util.List;

public record LlmCall(
        String id,
        String reviewId,
        Stage stage,
        String callKey,
        int attempt,
        String executor,
        String model,
        LlmCallStatus status,
        LlmRequest request,
        String rawOutput,
        List<String> errors,
        Long durationMs,
        Instant createdAt,
        Instant completedAt) {

    public LlmCall {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public LlmCall withResult(LlmCallStatus s, String raw, String usedModel, List<String> errs, Long ms, Instant at) {
        return new LlmCall(id, reviewId, stage, callKey, attempt, executor, usedModel != null ? usedModel : model, s,
                request, raw, errs, ms, createdAt, at);
    }

    public LlmCall withStatus(LlmCallStatus s) {
        return new LlmCall(id, reviewId, stage, callKey, attempt, executor, model, s, request, rawOutput, errors,
                durationMs, createdAt, completedAt);
    }
}
