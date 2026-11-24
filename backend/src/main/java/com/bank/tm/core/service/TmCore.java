package com.bank.tm.core.service;

import com.bank.tm.core.llm.LlmClient;
import com.bank.tm.core.llm.LlmGateway;
import com.bank.tm.core.llm.PromptLibrary;
import com.bank.tm.core.parsing.DocumentParser;
import com.bank.tm.core.parsing.DocumentParsingService;
import com.bank.tm.core.pipeline.DocumentStorage;
import com.bank.tm.core.pipeline.PipelineOrchestrator;
import com.bank.tm.core.pipeline.PipelineServices;
import com.bank.tm.core.pipeline.Stages;
import com.bank.tm.core.rag.EmbeddingProvider;
import com.bank.tm.core.rag.KnowledgeBase;
import com.bank.tm.core.rag.Retriever;
import com.bank.tm.core.risk.RiskEngine;
import com.bank.tm.core.risk.RiskRule;
import com.bank.tm.core.rules.ConditionEvaluator;
import com.bank.tm.core.rules.Operator;
import com.bank.tm.core.rules.OperatorRegistry;
import com.bank.tm.core.rules.RuleRepository;
import com.bank.tm.core.rules.ThreatCandidateEngine;
import com.bank.tm.core.settings.EndpointPolicy;
import com.bank.tm.core.settings.SecretCipher;
import com.bank.tm.core.settings.SettingsService;
import com.bank.tm.core.settings.SwitchableEmbeddingProvider;
import com.bank.tm.core.settings.SwitchableLlmClient;
import com.bank.tm.core.settings.SystemSettings;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.validation.ModelValidator;
import com.bank.tm.core.validation.ThreatValidator;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Çekirdek bileşenlerin tek noktadan bağlanması. Spring yapılandırması ve testler aynı fabrikayı kullanır;
 * böylece uygulamada çalışan bağlantılar testlerde de doğrulanmış olur.
 */
public final class TmCore {

    /**
     * initialSettings doluysa (uygulama) LLM/embedding istemcileri bu ayarlardan kurulur ve "Sistem parametreleri"
     * ekranından yönetilir; boşsa (testler, yerel deneme) verilen llmClient/embeddings kullanılır.
     */
    public record Config(Path datasetsDir, Path uploadsDir, ReviewStore store, LlmClient llmClient,
                         EmbeddingProvider embeddings, Executor executor, TmSettings settings, int llmMaxAttempts,
                         List<DocumentParser> extraParsers, List<Operator> extraOperators, List<RiskRule> codeRiskRules,
                         SystemSettings initialSettings, SecretCipher cipher, EndpointPolicy endpointPolicy) {

        public Config(Path datasetsDir, Path uploadsDir, ReviewStore store, LlmClient llmClient, EmbeddingProvider embeddings,
                      Executor executor, TmSettings settings, int llmMaxAttempts, List<DocumentParser> extraParsers,
                      List<Operator> extraOperators, List<RiskRule> codeRiskRules) {
            this(datasetsDir, uploadsDir, store, llmClient, embeddings, executor, settings, llmMaxAttempts, extraParsers,
                    extraOperators, codeRiskRules, null, new SecretCipher(null), EndpointPolicy.permissive());
        }
    }

    public final ReviewStore store;
    public final OperatorRegistry operators;
    public final RuleRepository rules;
    public final KnowledgeBase knowledgeBase;
    public final Retriever retriever;
    public final RiskEngine risk;
    public final LlmGateway llm;
    public final PipelineOrchestrator orchestrator;
    public final ReviewService reviews;
    public final ReportBuilder reports;
    public final SampleLibrary samples;
    public final EvaluationService evaluation;
    public final MetricsService metrics;
    public final SettingsService systemSettings;
    private final java.util.function.Supplier<TmSettings> live;

    private TmCore(Config c) {
        this.store = c.store();
        boolean managed = c.initialSettings() != null;
        SystemSettings initial = managed ? c.initialSettings()
                : SystemSettings.fromTmSettings(c.settings(), SystemSettings.defaults().llm(), SystemSettings.defaults().embedding(),
                SystemSettings.defaults().browserLlm());
        SwitchableLlmClient llmClient = new SwitchableLlmClient(c.llmClient() != null ? c.llmClient() : new com.bank.tm.core.llm.MockLlmClient());
        SwitchableEmbeddingProvider embeddingProvider = new SwitchableEmbeddingProvider(c.embeddings());
        this.operators = new OperatorRegistry();
        c.extraOperators().forEach(operators::register);
        this.rules = new RuleRepository(c.datasetsDir().resolve("rules"), operators);
        this.knowledgeBase = new KnowledgeBase(c.datasetsDir().resolve("knowledge-base"));
        this.retriever = new Retriever(knowledgeBase, embeddingProvider);
        this.risk = new RiskEngine(rules, c.codeRiskRules());
        this.llm = new LlmGateway(store, llmClient, c.llmMaxAttempts());
        this.systemSettings = new SettingsService(store, initial, c.cipher(), c.endpointPolicy(), llmClient, embeddingProvider, managed);
        systemSettings.addListener(s -> {
            if (managed || !"application.yml".equals(systemSettings.source())) llm.setMaxAttempts(s.llm().maxAttempts());
            retriever.clearVectorCache();
        });
        this.live = managed ? systemSettings::tmSettings : liveOrStatic(c.settings());
        DocumentStorage storage = new DocumentStorage(c.uploadsDir());
        PipelineServices services = new PipelineServices(store, storage,
                DocumentParsingService.withDefaults(c.extraParsers()), llm, new PromptLibrary(), new ModelValidator(),
                new ThreatValidator(), rules, new ThreatCandidateEngine(rules, new ConditionEvaluator(operators)),
                knowledgeBase, retriever, risk, this.live);
        this.orchestrator = new PipelineOrchestrator(store, Stages.all(services), c.executor(), llm);
        this.reports = new ReportBuilder(store, rules);
        this.samples = new SampleLibrary(c.datasetsDir().resolve("sample-projects"), c.datasetsDir().resolve("evaluation"));
        this.evaluation = new EvaluationService(store, samples);
        this.metrics = new MetricsService(store);
        this.reviews = new ReviewService(store, storage, orchestrator, llm, new ModelValidator(), risk, knowledgeBase,
                reports, samples, this.live);
    }

    /** Akış adımlarının gördüğü güncel ayarlar. */
    public TmSettings settings() {
        return live.get();
    }

    /** Test/yerel modda: ekrandan kayıt yapılana kadar verilen TmSettings, sonrasında kaydedilen ayarlar. */
    private java.util.function.Supplier<TmSettings> liveOrStatic(TmSettings fixed) {
        return () -> "application.yml".equals(systemSettings.source()) ? fixed : systemSettings.tmSettings();
    }

    public static TmCore create(Config config) {
        return new TmCore(config);
    }
}
