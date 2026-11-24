package com.bank.tm.core.service;

import com.bank.tm.core.domain.LlmMode;

/** Çekirdek ayarları (Spring katmanında application.yml'den doldurulur). */
public record TmSettings(
        LlmMode defaultLlmMode,
        boolean browserLlmEnabled,
        int modelExtractionMaxCharsServer,
        int modelExtractionMaxCharsBrowser,
        int threatBatchSizeServer,
        int threatBatchSizeBrowser,
        int retrievalPerCandidate,
        int retrievalMaxPerSource,
        double temperature) {

    public static TmSettings defaults() {
        return new TmSettings(LlmMode.SERVER, true, 60_000, 8_000, 12, 4, 4, 2, 0.1);
    }

    public int maxChars(LlmMode mode) {
        return mode == LlmMode.BROWSER ? modelExtractionMaxCharsBrowser : modelExtractionMaxCharsServer;
    }

    public int batchSize(LlmMode mode) {
        return mode == LlmMode.BROWSER ? threatBatchSizeBrowser : threatBatchSizeServer;
    }
}
