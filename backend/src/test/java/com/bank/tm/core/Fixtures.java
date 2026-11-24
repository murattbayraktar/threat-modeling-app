package com.bank.tm.core;

import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.llm.MockLlmClient;
import com.bank.tm.core.service.TmCore;
import com.bank.tm.core.service.TmSettings;
import com.bank.tm.core.store.InMemoryReviewStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Testler: gerçek datasets/ klasörü + bellek içi depo + mock LLM + senkron yürütücü. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path datasets() {
        for (Path p : List.of(Path.of("../datasets"), Path.of("datasets"), Path.of("../../datasets"))) {
            if (Files.isDirectory(p.resolve("rules"))) return p.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("datasets klasörü bulunamadı");
    }

    public static TmCore core() {
        return core(TmSettings.defaults());
    }

    public static TmCore core(TmSettings settings) {
        try {
            Path uploads = Files.createTempDirectory("tm-uploads");
            return TmCore.create(new TmCore.Config(datasets(), uploads, new InMemoryReviewStore(), new MockLlmClient(),
                    null, Runnable::run, settings, 2, List.of(), List.of(), List.of()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TmSettings browserSettings() {
        TmSettings d = TmSettings.defaults();
        return new TmSettings(LlmMode.BROWSER, true, d.modelExtractionMaxCharsServer(), d.modelExtractionMaxCharsBrowser(),
                d.threatBatchSizeServer(), d.threatBatchSizeBrowser(), d.retrievalPerCandidate(), d.retrievalMaxPerSource(),
                d.temperature());
    }
}
