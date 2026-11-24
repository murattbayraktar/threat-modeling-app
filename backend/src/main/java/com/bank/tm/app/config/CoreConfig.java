package com.bank.tm.app.config;

import com.bank.tm.app.parsing.PdfBoxParser;
import com.bank.tm.core.risk.RiskRule;
import com.bank.tm.core.rules.Operator;
import com.bank.tm.core.service.ReviewService;
import com.bank.tm.core.service.TmCore;
import com.bank.tm.core.service.TmSettings;
import com.bank.tm.core.settings.EndpointPolicy;
import com.bank.tm.core.settings.SecretCipher;
import com.bank.tm.core.settings.SystemSettings;
import com.bank.tm.core.store.JdbcReviewStore;
import com.bank.tm.core.store.ReviewStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Çekirdek bileşenleri Spring'e bağlar. Genişletme noktaları: Spring bean olarak tanımlanan
 * {@link RiskRule} (Java ile yazılmış risk kuralları) ve {@link Operator} (yeni koşul operatörleri)
 * otomatik olarak çekirdeğe eklenir.
 */
@Configuration
public class CoreConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService pipelineExecutor(TmProperties props) {
        return Executors.newFixedThreadPool(Math.max(1, props.pipeline().workerThreads()), r -> {
            Thread t = new Thread(r, "tm-pipeline");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public ReviewStore reviewStore(DataSource dataSource, TmProperties props) {
        try {
            Files.createDirectories(Path.of(props.dataDir()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new JdbcReviewStore(dataSource, props.db().dialect());
    }

    @Bean
    public TmCore tmCore(TmProperties props, ReviewStore store, ExecutorService pipelineExecutor,
                         ObjectProvider<RiskRule> codeRiskRules, ObjectProvider<Operator> extraOperators) {
        TmProperties.Pipeline p = props.pipeline();
        TmSettings settings = new TmSettings(p.defaultLlmMode(), p.browserLlmEnabled(),
                p.modelExtractionMaxCharsServer(), p.modelExtractionMaxCharsBrowser(), p.threatBatchSizeServer(),
                p.threatBatchSizeBrowser(), p.retrievalPerCandidate(), p.retrievalMaxPerSource(), p.temperature());
        // application.yml değerleri ilk ayarlardır; ekrandan kaydedilenler veritabanından yüklenip bunların yerine geçer.
        TmProperties.Llm l = props.llm();
        TmProperties.Embedding e = props.embedding();
        TmProperties.BrowserLlm b = props.browserLlm();
        boolean embeddingOn = e != null && notBlank(e.baseUrl()) && notBlank(e.model());
        SystemSettings initial = SystemSettings.fromTmSettings(settings,
                new SystemSettings.Llm(l.provider(), nz(l.baseUrl()), nz(l.apiKey()), nz(l.model()), l.timeoutSeconds(),
                        l.structuredOutputMode(), l.maxAttempts(), new LinkedHashMap<>()),
                new SystemSettings.Embedding(embeddingOn, e == null ? "" : nz(e.baseUrl()), e == null ? "" : nz(e.apiKey()),
                        e == null ? "" : nz(e.model())),
                new SystemSettings.BrowserLlm(p.browserLlmEnabled(), b == null ? "" : nz(b.defaultModel()), "/webllm-config.json"));
        TmProperties.Security sec = props.security();
        SecretCipher cipher = new SecretCipher(sec == null ? null : sec.settingsEncryptionKey());
        EndpointPolicy policy = new EndpointPolicy(sec == null || sec.blockPublicEndpoints() == null || sec.blockPublicEndpoints(),
                sec == null || sec.internalHostSuffixes() == null ? List.of() : sec.internalHostSuffixes());
        Path data = Path.of(props.dataDir());
        return TmCore.create(new TmCore.Config(Path.of(props.datasetsDir()).toAbsolutePath().normalize(),
                data.resolve("uploads").toAbsolutePath(), store, null, null, pipelineExecutor, settings,
                l.maxAttempts(), List.of(new PdfBoxParser()), extraOperators.orderedStream().toList(),
                codeRiskRules.orderedStream().toList(), initial, cipher, policy));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Bean
    public ReviewService reviewService(TmCore core) {
        return core.reviews;
    }
}
