package com.bank.tm.core.rag;

import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** POST {baseUrl}/embeddings – OpenAI uyumlu embedding uç noktası (kurum içi gateway). */
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String name() { return "openai-compatible:" + model; }

    @Override
    public float[] embed(String text) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("model", model, "input", text))));
        if (apiKey != null && !apiKey.isBlank()) req.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("Embedding hatası " + res.statusCode());
        JsonNode vec = Json.tree(res.body()).path("data").path(0).path("embedding");
        float[] out = new float[vec.size()];
        for (int i = 0; i < out.length; i++) out[i] = (float) vec.get(i).asDouble();
        return out;
    }
}
