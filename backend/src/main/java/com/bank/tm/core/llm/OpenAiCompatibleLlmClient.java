package com.bank.tm.core.llm;

import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI uyumlu /chat/completions uç noktası. Kurum içi gen AI ortamlarının çoğu bu arayüzü sunar.
 * structuredOutputMode:
 * JSON_SCHEMA (response_format json_schema + strict), JSON_OBJECT (yalnızca JSON modu), NONE (şema prompt içinde).
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    public enum StructuredOutputMode { JSON_SCHEMA, JSON_OBJECT, NONE }

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final StructuredOutputMode mode;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model, Duration timeout,
                                     StructuredOutputMode mode) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.mode = mode;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String name() { return "openai-compatible"; }

    @Override
    public String model() { return model; }

    @Override
    public LlmResponse complete(LlmRequest req) throws Exception {
        long start = System.currentTimeMillis();
        String user = req.userPrompt();
        if (mode != StructuredOutputMode.JSON_SCHEMA) {
            user += "\n\nYanıtın şu JSON Schema'ya uymalıdır:\n" + Json.write(SchemaRegistry.forProvider(req.schema()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", req.temperature());
        if (req.maxOutputTokens() != null) body.put("max_tokens", req.maxOutputTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", req.systemPrompt()),
                Map.of("role", "user", "content", user)));
        switch (mode) {
            case JSON_SCHEMA -> body.put("response_format", Map.of("type", "json_schema", "json_schema",
                    Map.of("name", req.schemaName(), "strict", true, "schema", SchemaRegistry.forProvider(req.schema()))));
            case JSON_OBJECT -> body.put("response_format", Map.of("type", "json_object"));
            case NONE -> { }
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("LLM uç noktası " + res.statusCode() + " döndü: "
                    + res.body().substring(0, Math.min(500, res.body().length())));
        }
        JsonNode json = Json.tree(res.body());
        String content = json.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null) throw new IllegalStateException("Yanıtta choices[0].message.content yok");
        return new LlmResponse(content, json.path("model").asText(model), System.currentTimeMillis() - start);
    }
}
