package com.bank.tm.core.domain;

import java.util.List;

/** origin: AI (LLM önerisi), RULE (kural şablonu yedeği) veya EXPERT (uzman ekledi/düzenledi). */
public record Mitigation(String title, String description, List<String> sourceIds, String origin) {
    public Mitigation {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
