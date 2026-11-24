package com.bank.tm.core.settings;

import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.service.TmSettings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Sistem parametreleri" ekranından yönetilen, çalışırken değiştirilebilen ayarlar.
 * İlk değerler application.yml'den gelir; ekrandan kaydedilen hali veritabanında (app_setting) tutulur ve önceliklidir.
 * API anahtarları veritabanına şifreli yazılır, API yanıtlarında hiçbir zaman açık dönmez.
 */
public record SystemSettings(Llm llm, Embedding embedding, BrowserLlm browserLlm, Extraction extraction, Pipeline pipeline) {

    /** Sunucu LLM'i. taskModels: adım bazında model (boşsa varsayılan model kullanılır). */
    public record Llm(String provider, String baseUrl, String apiKey, String model, int timeoutSeconds,
                      String structuredOutputMode, int maxAttempts, Map<String, String> taskModels) {
    }

    /** RAG için isteğe bağlı embedding servisi. Kapalıysa yalnızca BM25 araması yapılır. */
    public record Embedding(boolean enabled, String baseUrl, String apiKey, String model) {
    }

    /** Tarayıcıda (WebLLM/WebGPU) çalışan LLM. configUrl: kurum içi model listesi (webllm-config.json). */
    public record BrowserLlm(boolean enabled, String defaultModel, String configUrl) {
    }

    /**
     * Doküman metin çıkarımı. ocrMode: BROWSER (tesseract.js, tarayıcıda) | DISABLED.
     * Sunucu tarafı OCR (ör. on-prem LandingAI) Faz 2'de yeni bir değer olarak eklenecek.
     */
    public record Extraction(boolean browserPdfText, String ocrMode, String ocrLanguages, String tesseractWorkerPath,
                             String tesseractCorePath, String tesseractLangPath) {
    }

    public record Pipeline(String defaultLlmMode, double temperature, int modelExtractionMaxCharsServer,
                           int modelExtractionMaxCharsBrowser, int threatBatchSizeServer, int threatBatchSizeBrowser,
                           int retrievalPerCandidate, int retrievalMaxPerSource) {
    }

    public static final String PROVIDER_MOCK = "mock";
    public static final String PROVIDER_OPENAI = "openai-compatible";

    /** Akış adımlarının kullandığı ayar görünümü. */
    public TmSettings toTmSettings() {
        Pipeline p = pipeline;
        return new TmSettings(LlmMode.valueOf(p.defaultLlmMode()), browserLlm.enabled(), p.modelExtractionMaxCharsServer(),
                p.modelExtractionMaxCharsBrowser(), p.threatBatchSizeServer(), p.threatBatchSizeBrowser(),
                p.retrievalPerCandidate(), p.retrievalMaxPerSource(), p.temperature());
    }

    /** application.yml olmadan (testler, yerel deneme) kullanılan varsayılanlar. */
    public static SystemSettings defaults() {
        return fromTmSettings(TmSettings.defaults(), new Llm(PROVIDER_MOCK, "", "", "heuristic-mock-v1", 120, "JSON_SCHEMA", 2,
                new LinkedHashMap<>()), new Embedding(false, "", "", ""), new BrowserLlm(true, "Qwen2.5-3B-Instruct-q4f16_1-MLC",
                "/webllm-config.json"));
    }

    public static SystemSettings fromTmSettings(TmSettings t, Llm llm, Embedding embedding, BrowserLlm browser) {
        return new SystemSettings(llm, embedding, new BrowserLlm(t.browserLlmEnabled(), browser.defaultModel(), browser.configUrl()),
                new Extraction(true, "BROWSER", "tur+eng", "", "", ""),
                new Pipeline(t.defaultLlmMode().name(), t.temperature(), t.modelExtractionMaxCharsServer(),
                        t.modelExtractionMaxCharsBrowser(), t.threatBatchSizeServer(), t.threatBatchSizeBrowser(),
                        t.retrievalPerCandidate(), t.retrievalMaxPerSource()));
    }

    public SystemSettings withLlm(Llm l) { return new SystemSettings(l, embedding, browserLlm, extraction, pipeline); }

    public SystemSettings withEmbedding(Embedding e) { return new SystemSettings(llm, e, browserLlm, extraction, pipeline); }
}
