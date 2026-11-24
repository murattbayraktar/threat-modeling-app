package com.bank.tm.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sağlayıcıdan bağımsız LLM isteği. schema: structured output için JSON Schema. input: istemin dayandığı
 * yapılandırılmış veri (izlenebilirlik ve mock sağlayıcı için; gerçek sağlayıcıya yalnızca prompt gider).
 */
public record LlmRequest(
        LlmTask task,
        String promptVersion,
        String systemPrompt,
        String userPrompt,
        String schemaName,
        JsonNode schema,
        double temperature,
        Integer maxOutputTokens,
        JsonNode input) {

    /** Önceki çıktı şemaya uymadığında hataları bildirerek yeniden deneme isteği. */
    public LlmRequest withRepair(String previousOutput, java.util.List<String> errors) {
        String repair = userPrompt + "\n\n---\nÖNCEKİ YANITIN ŞEMAYA UYMADI. Hatalar:\n- "
                + String.join("\n- ", errors.subList(0, Math.min(errors.size(), 15)))
                + "\nYalnızca şemaya uygun, geçerli bir JSON nesnesi döndür.";
        return new LlmRequest(task, promptVersion, systemPrompt, repair, schemaName, schema, temperature,
                maxOutputTokens, input);
    }
}
