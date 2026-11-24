package com.bank.tm.core.llm;

import com.bank.tm.core.model.Confidence;
import com.bank.tm.core.model.ThreatCandidate;
import com.bank.tm.core.model.ThreatGenerationOutput;
import com.bank.tm.core.model.ThreatGenerationOutput.LlmMitigation;
import com.bank.tm.core.model.ThreatGenerationOutput.LlmThreat;
import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mock tehdit üretici: her adayı şablon metinle ve RAG'den gelen kaynakların önlem metinleriyle doldurur.
 * Gerçek LLM'in yaptığı "bağlama uyarlama" burada şablon düzeyindedir.
 */
public final class MockThreatGenerator {

    record Source(String id, String source, String title, String text, String mitigation, List<String> candidateIds) {
    }

    private MockThreatGenerator() {
    }

    public static ThreatGenerationOutput generate(JsonNode input) {
        List<ThreatCandidate> candidates = Json.MAPPER.convertValue(input.get("candidates"), new TypeReference<>() { });
        List<Source> sources = Json.MAPPER.convertValue(input.get("sources"), new TypeReference<>() { });
        List<LlmThreat> out = new ArrayList<>();
        for (ThreatCandidate c : candidates) {
            List<Source> mine = sources.stream().filter(s -> s.candidateIds().contains(c.id()))
                    .sorted(Comparator.comparingInt(s -> "BANK_CONTROL".equals(s.source()) ? 0 : 1)).toList();
            List<LlmMitigation> mitigations = new ArrayList<>();
            for (Source s : mine) {
                if (mitigations.size() >= 2) break;
                if (s.mitigation() == null || s.mitigation().isBlank()) continue;
                mitigations.add(new LlmMitigation(s.title().replace("[ÖRNEK] ", ""), s.mitigation(), List.of(s.id())));
            }
            List<String> sourceIds = mine.stream().limit(3).map(Source::id).toList();
            out.add(new LlmThreat(c.id(), true, null, c.title(), c.description(), scenario(c), c.stride(),
                    c.targetId(), c.defaultLikelihood(), c.defaultImpact(),
                    "Kural varsayılanları ve hedefin modeldeki özellikleri esas alındı (mock).", mitigations, sourceIds,
                    Confidence.MEDIUM, List.of()));
        }
        return new ThreatGenerationOutput(out);
    }

    private static String scenario(ThreatCandidate c) {
        String t = c.targetName();
        return switch (c.stride()) {
            case SPOOFING -> "Saldırgan ele geçirilmiş veya sahte kimlik bilgileriyle " + t + " hedefine meşru bir taraf gibi istek gönderir.";
            case TAMPERING -> "Saldırgan " + t + " üzerinde işlenen veya iletilen veriyi değiştirerek iş sonucunu etkiler.";
            case REPUDIATION -> t + " üzerinde yapılan bir işlem yeterli kayıt olmadığı için sonradan inkâr edilir.";
            case INFORMATION_DISCLOSURE -> "Yetkisiz bir taraf " + t + " üzerindeki hassas veriyi okur veya dışarı çıkarır.";
            case DENIAL_OF_SERVICE -> "Yoğun veya kötü niyetli istekler " + t + " hizmetini yavaşlatır ya da durdurur.";
            case ELEVATION_OF_PRIVILEGE -> "Düşük yetkili bir kullanıcı veya servis " + t + " üzerinde yetkisi olmayan işlemleri gerçekleştirir.";
        };
    }

}
