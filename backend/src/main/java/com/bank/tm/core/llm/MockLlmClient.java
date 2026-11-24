package com.bank.tm.core.llm;

import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/**
 * Ağ erişimi olmadan çalışan deterministik sağlayıcı. İstemi değil, isteğin yapılandırılmış girdisini (input)
 * kullanır. Geliştirme, demo ve otomatik testler içindir.
 */
public class MockLlmClient implements LlmClient {

    @Override
    public String name() { return "mock"; }

    @Override
    public String model() { return "heuristic-mock-v1"; }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.currentTimeMillis();
        Object output = switch (request.task()) {
            case MODEL_EXTRACTION -> {
                List<ParsedDocument> docs = Json.MAPPER.convertValue(request.input().get("documents"),
                        new TypeReference<>() { });
                String name = request.input().path("systemName").asText(null);
                yield HeuristicModelExtractor.extract(docs, name);
            }
            case THREAT_GENERATION -> MockThreatGenerator.generate(request.input());
        };
        return new LlmResponse(Json.write(output), model(), System.currentTimeMillis() - start);
    }
}
