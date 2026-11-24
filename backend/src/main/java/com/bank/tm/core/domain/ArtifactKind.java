package com.bank.tm.core.domain;

/** Adımlar arası taşınan ara çıktılar (review_artifact tablosu). */
public enum ArtifactKind {
    MODEL_EXTRACTION_OUTPUT,
    THREAT_CANDIDATES,
    KNOWLEDGE_RETRIEVAL,
    THREAT_GENERATION_OUTPUT,
    THREAT_VALIDATION_REPORT,
    RISK_SUMMARY,
    REPORT_SNAPSHOT
}
