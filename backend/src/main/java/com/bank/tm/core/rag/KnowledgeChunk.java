package com.bank.tm.core.rag;

import java.util.List;

/** Bilgi tabanındaki tek kayıt (datasets/knowledge-base/*.jsonl satırı). */
public record KnowledgeChunk(String id, String source, String title, String text, String mitigation,
                             List<String> tags, String reference) {
    public KnowledgeChunk {
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (mitigation == null) mitigation = "";
    }

    public String searchableText() {
        return title + " " + text + " " + mitigation + " " + String.join(" ", tags);
    }
}
