package com.bank.tm.app.config;

import com.bank.tm.core.domain.LlmMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** application.yml içindeki tm.* ayarları. */
@ConfigurationProperties(prefix = "tm")
public record TmProperties(
        String dataDir,
        String datasetsDir,
        String frontendDist,
        Db db,
        Pipeline pipeline,
        Llm llm,
        Embedding embedding,
        BrowserLlm browserLlm,
        Security security) {

    public record Db(String dialect) {
    }

    public record Pipeline(int workerThreads, LlmMode defaultLlmMode, boolean browserLlmEnabled,
                           int modelExtractionMaxCharsServer, int modelExtractionMaxCharsBrowser,
                           int threatBatchSizeServer, int threatBatchSizeBrowser, int retrievalPerCandidate,
                           int retrievalMaxPerSource, double temperature) {
    }

    public record Llm(String provider, String baseUrl, String apiKey, String model, int timeoutSeconds,
                      String structuredOutputMode, int maxAttempts) {
    }

    public record Embedding(String baseUrl, String apiKey, String model) {
    }

    public record BrowserLlm(String defaultModel, String modelBaseUrl) {
    }

    /**
     * settingsEncryptionKey: API anahtarlarını veritabanında şifrelemek için parola (ortam değişkeni / kasa).
     * blockPublicEndpoints (varsayılan false) + internalHostSuffixes: true ise ekrandan yalnızca kurum içi adres girilebilir.
     * settingsAdmins: sistem parametrelerini değiştirebilecek kullanıcılar (boşsa herkes; yalnızca geliştirme için).
     */
    public record Security(String settingsEncryptionKey, Boolean blockPublicEndpoints, List<String> internalHostSuffixes,
                           List<String> settingsAdmins) {
    }
}
