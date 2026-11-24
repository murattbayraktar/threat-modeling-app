package com.bank.tm.core;

import com.bank.tm.core.rag.KnowledgeBase;
import com.bank.tm.core.rag.RetrievedChunk;
import com.bank.tm.core.rag.Retriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieverTest {

    private final KnowledgeBase kb = new KnowledgeBase(Fixtures.datasets().resolve("knowledge-base"));
    private final Retriever retriever = new Retriever(kb, null);

    @Test
    void loadsAllSources() {
        assertTrue(kb.loadWarnings().isEmpty(), kb.loadWarnings().toString());
        assertTrue(kb.countsBySource().keySet().containsAll(List.of("STRIDE", "OWASP_TOP10_2021", "OWASP_API_TOP10_2023",
                "MITRE_ATTACK", "BANK_CONTROL", "PAST_REVIEW")));
    }

    @Test
    void sqlInjectionQueryFindsParameterizedQueryControl() {
        List<RetrievedChunk> hits = retriever.search(new Retriever.Query("SQL enjeksiyonu parametreli sorgu",
                Set.of("TAMPERING", "DATABASE"), null, 5, 0));
        List<String> ids = hits.stream().map(h -> h.chunk().id()).toList();
        assertTrue(ids.contains("KAT-APP-02"), ids.toString());
        assertTrue(ids.contains("OWASP-A03"), ids.toString());
    }

    @Test
    void turkishInflectionsMatch() {
        // "doğrulanmaması" ile "doğrulama" aynı köke iner
        List<RetrievedChunk> hits = retriever.search(new Retriever.Query("servisler arası kimliğin doğrulanmaması",
                Set.of(), Set.of("BANK_CONTROL"), 3, 0));
        assertEquals("KAT-IAM-04", hits.get(0).chunk().id());
    }
}
