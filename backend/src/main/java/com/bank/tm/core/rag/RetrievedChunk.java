package com.bank.tm.core.rag;

/** Sorgu sonucu: kayıt + skor bileşenleri (izlenebilirlik için). */
public record RetrievedChunk(KnowledgeChunk chunk, double score, double lexicalScore, double vectorScore) {
}
