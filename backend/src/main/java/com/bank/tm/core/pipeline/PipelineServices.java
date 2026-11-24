package com.bank.tm.core.pipeline;

import com.bank.tm.core.llm.LlmGateway;
import com.bank.tm.core.llm.PromptLibrary;
import com.bank.tm.core.parsing.DocumentParsingService;
import com.bank.tm.core.rag.KnowledgeBase;
import com.bank.tm.core.rag.Retriever;
import com.bank.tm.core.risk.RiskEngine;
import com.bank.tm.core.rules.RuleRepository;
import com.bank.tm.core.rules.ThreatCandidateEngine;
import com.bank.tm.core.service.TmSettings;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.validation.ModelValidator;
import com.bank.tm.core.validation.ThreatValidator;

/** Adımların ihtiyaç duyduğu servisler. */
public record PipelineServices(
        ReviewStore store,
        DocumentStorage storage,
        DocumentParsingService parsing,
        LlmGateway llm,
        PromptLibrary prompts,
        ModelValidator modelValidator,
        ThreatValidator threatValidator,
        RuleRepository rules,
        ThreatCandidateEngine candidates,
        KnowledgeBase knowledgeBase,
        Retriever retriever,
        RiskEngine risk,
        java.util.function.Supplier<TmSettings> settingsSource) {

    /** Güncel ayarlar; sistem parametreleri ekranından değiştirildiğinde bir sonraki adımda devreye girer. */
    public TmSettings settings() {
        return settingsSource.get();
    }
}
