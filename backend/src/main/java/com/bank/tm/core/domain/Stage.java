package com.bank.tm.core.domain;

/** Faz 1 akışının adımları (sırası önemlidir). */
public enum Stage {
    UPLOAD("Yükleme", Actor.SYSTEM),
    PARSING("Ayrıştırma", Actor.SYSTEM),
    MODEL_EXTRACTION("Model çıkarımı", Actor.LLM),
    MODEL_VALIDATION("Model doğrulama", Actor.SYSTEM),
    MODEL_REVIEW("Model onayı", Actor.ANALYST),
    THREAT_CANDIDATES("Aday tehditler (kurallar)", Actor.SYSTEM),
    KNOWLEDGE_RETRIEVAL("Bilgi getirme (RAG)", Actor.SYSTEM),
    THREAT_GENERATION("Tehdit üretimi", Actor.LLM),
    THREAT_VALIDATION("Tehdit doğrulama", Actor.SYSTEM),
    RISK_ASSESSMENT("Risk değerlendirmesi", Actor.SYSTEM),
    EXPERT_REVIEW("Uzman incelemesi", Actor.ANALYST),
    REPORT("Rapor", Actor.SYSTEM);

    public enum Actor { SYSTEM, LLM, ANALYST }

    private final String label;
    private final Actor actor;

    Stage(String label, Actor actor) {
        this.label = label;
        this.actor = actor;
    }

    public String label() { return label; }

    public Actor actor() { return actor; }

    public boolean isAfterOrEqual(Stage other) { return ordinal() >= other.ordinal(); }
}
