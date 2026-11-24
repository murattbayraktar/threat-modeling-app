package com.bank.tm.core.settings;

import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** OpenAI uyumlu bir uç noktanın tanımlı modellerini (GET {baseUrl}/models) listeler. */
public final class ModelCatalog {

    private ModelCatalog() { }

    public record Result(List<String> models, String host, boolean external) {
    }

    public static List<String> fetch(String baseUrl, String apiKey) throws Exception {
        String base = baseUrl.strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/models")).timeout(Duration.ofSeconds(20)).GET();
        if (apiKey != null && !apiKey.isBlank()) b.header("Authorization", "Bearer " + apiKey);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("Model listesi alınamadı (" + res.statusCode() + "): "
                    + res.body().substring(0, Math.min(300, res.body().length())));
        }
        return parse(Json.tree(res.body()));
    }

    /** OpenAI biçimi {"data":[{"id":..}]}; bazı ağ geçitleri {"models":[..]} ya da düz dizi döner. */
    public static List<String> parse(JsonNode json) {
        JsonNode arr = json.isArray() ? json : json.has("data") ? json.get("data") : json.path("models");
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String id = n.isTextual() ? n.asText() : n.hasNonNull("id") ? n.get("id").asText() : n.path("name").asText("");
                if (!id.isBlank() && !out.contains(id)) out.add(id);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
