package com.bank.tm.core.domain;

import java.time.Instant;
import java.util.Map;

public record Review(
        String id,
        String code,
        String title,
        String businessUnit,
        String projectType,
        String dataClassification,
        String owner,
        String analyst,
        LlmMode llmMode,
        ReviewStatus status,
        Stage currentStage,
        String sampleName,
        Map<String, Object> intakeAnswers,
        boolean expertReviewCompleted,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public Review {
        intakeAnswers = intakeAnswers == null ? Map.of() : Map.copyOf(intakeAnswers);
    }

    public Review withState(ReviewStatus s, Stage stage, Instant now) {
        return new Review(id, code, title, businessUnit, projectType, dataClassification, owner, analyst, llmMode, s,
                stage, sampleName, intakeAnswers, expertReviewCompleted, createdBy, createdAt, now);
    }

    public Review withExpertReviewCompleted(boolean v, Instant now) {
        return new Review(id, code, title, businessUnit, projectType, dataClassification, owner, analyst, llmMode,
                status, currentStage, sampleName, intakeAnswers, v, createdBy, createdAt, now);
    }

    public Review withLlmMode(LlmMode mode, Instant now) {
        return new Review(id, code, title, businessUnit, projectType, dataClassification, owner, analyst, mode,
                status, currentStage, sampleName, intakeAnswers, expertReviewCompleted, createdBy, createdAt, now);
    }
}
