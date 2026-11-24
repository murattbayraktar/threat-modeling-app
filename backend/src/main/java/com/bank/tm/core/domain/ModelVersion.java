package com.bank.tm.core.domain;

import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.validation.ValidationIssue;

import java.time.Instant;
import java.util.List;

/** Sistem modelinin sürümü. source: AI (LLM çıktısı + doğrulama) veya ANALYST (elle düzenleme). */
public record ModelVersion(
        String id,
        String reviewId,
        int version,
        String source,
        SystemModel model,
        List<ValidationIssue> issues,
        String createdBy,
        Instant createdAt,
        String approvedBy,
        Instant approvedAt) {

    public ModelVersion {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean approved() { return approvedAt != null; }

    public ModelVersion approve(String by, Instant at) {
        return new ModelVersion(id, reviewId, version, source, model, issues, createdBy, createdAt, by, at);
    }
}
