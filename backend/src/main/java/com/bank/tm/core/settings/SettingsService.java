package com.bank.tm.core.settings;

import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.llm.LlmClient;
import com.bank.tm.core.llm.LlmRequest;
import com.bank.tm.core.llm.LlmResponse;
import com.bank.tm.core.llm.LlmTask;
import com.bank.tm.core.llm.MockLlmClient;
import com.bank.tm.core.llm.OpenAiCompatibleLlmClient;
import com.bank.tm.core.llm.StructuredOutput;
import com.bank.tm.core.rag.EmbeddingProvider;
import com.bank.tm.core.rag.OpenAiCompatibleEmbeddingProvider;
import com.bank.tm.core.service.TmSettings;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Sistem parametrelerinin tek sahibi: yükler, doğrular, şifreleyip saklar, istemcileri yeniden kurar ve
 * değişikliği dinleyicilere (LLM gateway, RAG) bildirir. Uygulama yeniden başlatılmadan devreye girer.
 *
 * Gizli alanlar (API anahtarları) için sözleşme:
 *  - Okumada hiçbir zaman açık değer dönmez; yalnızca "kayıtlı mı" ve son 4 karakter ipucu döner.
 *  - Kaydederken boş değer = mevcut anahtarı koru, {@link #CLEAR} = anahtarı sil, diğer değer = yeni anahtar.
 */
public final class SettingsService {

    public static final String KEY = "system";
    public static final String CLEAR = "__CLEAR__";
    private static final Set<String> STRUCTURED_MODES = Set.of("JSON_SCHEMA", "JSON_OBJECT", "NONE");
    private static final Set<String> OCR_MODES = Set.of("BROWSER", "DISABLED");

    /** Ekrana dönen görünüm: anahtarlar maskeli. */
    public record View(SystemSettings settings, Map<String, SecretStatus> secrets, Policy policy, String source) {
    }

    public record SecretStatus(boolean set, String hint) {
    }

    public record Policy(boolean encryptionEnabled, boolean blockPublicEndpoints, List<String> internalHostSuffixes) {
    }

    public record TestResult(String target, String model, boolean ok, String message, long durationMs) {
    }

    private final ReviewStore store;
    private final SystemSettings initial;
    private final SecretCipher cipher;
    private final EndpointPolicy policy;
    private final SwitchableLlmClient llm;
    private final SwitchableEmbeddingProvider embeddings;
    private final List<Consumer<SystemSettings>> listeners = new CopyOnWriteArrayList<>();
    private volatile SystemSettings current;
    private volatile String source = "application.yml";

    public SettingsService(ReviewStore store, SystemSettings initial, SecretCipher cipher, EndpointPolicy policy,
                           SwitchableLlmClient llm, SwitchableEmbeddingProvider embeddings, boolean applyOnStart) {
        this.store = store;
        this.initial = initial;
        this.cipher = cipher;
        this.policy = policy;
        this.llm = llm;
        this.embeddings = embeddings;
        this.current = loadStored().orElse(initial);
        // applyOnStart=false: istemciler dışarıdan verildi (testler, yerel deneme); ilk kayda kadar onlar kullanılır
        if (applyOnStart || !"application.yml".equals(source)) apply(current);
    }

    public void addListener(Consumer<SystemSettings> l) {
        listeners.add(l);
        l.accept(current);
    }

    /** İç kullanım: açık anahtarlarla güncel ayarlar. */
    public SystemSettings current() { return current; }

    public TmSettings tmSettings() { return current.toTmSettings(); }

    /** "application.yml" ya da "veritabanı" (ekrandan kaydedilmiş). */
    public String source() { return source; }

    public View view() {
        SystemSettings s = current;
        Map<String, SecretStatus> secrets = new LinkedHashMap<>();
        secrets.put("llm.apiKey", status(s.llm().apiKey()));
        secrets.put("embedding.apiKey", status(s.embedding().apiKey()));
        SystemSettings masked = s.withLlm(withKey(s.llm(), "")).withEmbedding(withKey(s.embedding(), ""));
        return new View(masked, secrets, new Policy(cipher.enabled(), policy.blockPublic(), policy.internalSuffixes()), source);
    }

    public List<ReviewStore.SettingChange> history(int limit) {
        return store.listSettingHistory(KEY, limit);
    }

    /** Ekrandan gelen taslağı doğrular, kaydeder ve uygular. */
    public View update(SystemSettings draft, String actor) {
        SystemSettings resolved = resolveSecrets(normalize(draft));
        validate(resolved);
        String summary = diff(current, resolved);
        if (summary.isEmpty()) return view();
        SystemSettings toStore = resolved.withLlm(withKey(resolved.llm(), cipher.encrypt(resolved.llm().apiKey())))
                .withEmbedding(withKey(resolved.embedding(), cipher.encrypt(resolved.embedding().apiKey())));
        store.putSetting(KEY, Json.write(toStore), actor, summary);
        current = resolved;
        source = "veritabanı";
        apply(resolved);
        return view();
    }

    /** Kaydedilmiş değerleri silip application.yml değerlerine döner. */
    public View resetToDefaults(String actor) {
        store.putSetting(KEY, Json.write(initial.withLlm(withKey(initial.llm(), cipher.encrypt(initial.llm().apiKey())))
                .withEmbedding(withKey(initial.embedding(), cipher.encrypt(initial.embedding().apiKey())))), actor, "Varsayılan değerlere dönüldü");
        current = initial;
        source = "application.yml";
        apply(initial);
        return view();
    }

    /** Kaydetmeden bağlantıyı dener. target: llm | embedding. */
    public List<TestResult> test(SystemSettings draft, String target) {
        SystemSettings s = resolveSecrets(normalize(draft));
        List<TestResult> out = new ArrayList<>();
        if ("embedding".equals(target)) {
            SystemSettings.Embedding e = s.embedding();
            long t0 = System.currentTimeMillis();
            try {
                policy.check("Embedding adresi", e.baseUrl());
                if (blank(e.baseUrl()) || blank(e.model())) throw new IllegalArgumentException("Adres ve model zorunlu");
                float[] v = new OpenAiCompatibleEmbeddingProvider(e.baseUrl(), e.apiKey(), e.model()).embed("bağlantı testi");
                out.add(new TestResult("embedding", e.model(), true, v.length + " boyutlu vektör döndü", System.currentTimeMillis() - t0));
            } catch (Exception ex) {
                out.add(new TestResult("embedding", e.model(), false, message(ex), System.currentTimeMillis() - t0));
            }
            return out;
        }
        SystemSettings.Llm l = s.llm();
        if (SystemSettings.PROVIDER_MOCK.equals(l.provider())) {
            out.add(new TestResult("llm", "heuristic-mock-v1", true, "Mock sağlayıcı ağa çıkmaz; her zaman kullanılabilir.", 0));
            return out;
        }
        List<String> models = new ArrayList<>();
        models.add(l.model());
        l.taskModels().values().stream().filter(m -> !blank(m) && !models.contains(m.strip())).forEach(m -> models.add(m.strip()));
        for (String model : models) {
            long t0 = System.currentTimeMillis();
            try {
                policy.check("LLM adresi", l.baseUrl());
                LlmClient client = openAi(l, model);
                JsonNode schema = Json.tree("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"ok\",\"model\"],"
                        + "\"properties\":{\"ok\":{\"type\":\"boolean\"},\"model\":{\"type\":\"string\"}}}");
                LlmResponse res = client.complete(new LlmRequest(null, "connection-test", "Sen bir bağlantı testi yanıtlayıcısısın.",
                        "Yalnızca şu JSON'u döndür: {\"ok\": true, \"model\": \"<model adın>\"}", "connection_test", schema, 0, 64,
                        Json.MAPPER.createObjectNode()));
                StructuredOutput.Result r = StructuredOutput.parse(res.text(), schema);
                out.add(new TestResult("llm", model, true, r.valid() ? "Yanıt alındı, JSON şemasına uygun (" + l.structuredOutputMode() + ")"
                        : "Yanıt alındı ama şemaya uymadı: " + String.join("; ", r.errors()), res.durationMs()));
            } catch (Exception ex) {
                out.add(new TestResult("llm", model, false, message(ex), System.currentTimeMillis() - t0));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ iç işler

    private void apply(SystemSettings s) {
        SystemSettings.Llm l = s.llm();
        if (SystemSettings.PROVIDER_MOCK.equals(l.provider())) {
            llm.replace(new MockLlmClient(), Map.of());
        } else {
            Map<LlmTask, LlmClient> perTask = new EnumMap<>(LlmTask.class);
            l.taskModels().forEach((task, model) -> {
                if (!blank(model) && !model.strip().equals(l.model())) perTask.put(LlmTask.valueOf(task), openAi(l, model.strip()));
            });
            llm.replace(openAi(l, l.model()), perTask);
        }
        SystemSettings.Embedding e = s.embedding();
        EmbeddingProvider next = e.enabled() && !blank(e.baseUrl()) && !blank(e.model())
                ? new OpenAiCompatibleEmbeddingProvider(e.baseUrl(), e.apiKey(), e.model()) : null;
        embeddings.replace(next);
        listeners.forEach(x -> x.accept(s));
    }

    private static LlmClient openAi(SystemSettings.Llm l, String model) {
        return new OpenAiCompatibleLlmClient(l.baseUrl(), l.apiKey(), model, Duration.ofSeconds(l.timeoutSeconds()),
                OpenAiCompatibleLlmClient.StructuredOutputMode.valueOf(l.structuredOutputMode()));
    }

    private java.util.Optional<SystemSettings> loadStored() {
        return store.getSetting(KEY).map(json -> {
            // Eski kayıtlarda olmayan alanlar application.yml değerleriyle tamamlanır
            ObjectNode merged = (ObjectNode) Json.MAPPER.valueToTree(initial);
            deepMerge(merged, Json.tree(json));
            SystemSettings s = Json.MAPPER.convertValue(merged, SystemSettings.class);
            source = "veritabanı";
            return normalize(s.withLlm(withKey(s.llm(), cipher.decrypt(s.llm().apiKey())))
                    .withEmbedding(withKey(s.embedding(), cipher.decrypt(s.embedding().apiKey()))));
        });
    }

    private static void deepMerge(ObjectNode target, JsonNode overlay) {
        overlay.fields().forEachRemaining(e -> {
            JsonNode existing = target.get(e.getKey());
            if (existing instanceof ObjectNode o && e.getValue().isObject() && !"taskModels".equals(e.getKey())) deepMerge(o, e.getValue());
            else if (!e.getValue().isNull()) target.set(e.getKey(), e.getValue());
        });
    }

    private SystemSettings resolveSecrets(SystemSettings d) {
        return d.withLlm(withKey(d.llm(), secret(d.llm().apiKey(), current.llm().apiKey())))
                .withEmbedding(withKey(d.embedding(), secret(d.embedding().apiKey(), current.embedding().apiKey())));
    }

    private static String secret(String incoming, String existing) {
        if (incoming == null || incoming.isEmpty()) return existing == null ? "" : existing;
        if (CLEAR.equals(incoming)) return "";
        return incoming.strip();
    }

    private static SystemSettings normalize(SystemSettings s) {
        SystemSettings.Llm l = s.llm();
        Map<String, String> tm = new LinkedHashMap<>();
        for (LlmTask t : LlmTask.values()) {
            String v = l.taskModels() == null ? null : l.taskModels().get(t.name());
            tm.put(t.name(), v == null ? "" : v.strip());
        }
        SystemSettings.Llm nl = new SystemSettings.Llm(trim(l.provider()), trim(l.baseUrl()), l.apiKey(), trim(l.model()),
                l.timeoutSeconds(), trim(l.structuredOutputMode()), l.maxAttempts(), tm);
        SystemSettings.Embedding e = s.embedding();
        SystemSettings.Embedding ne = new SystemSettings.Embedding(e.enabled(), trim(e.baseUrl()), e.apiKey(), trim(e.model()));
        SystemSettings.Extraction x = s.extraction();
        SystemSettings.Extraction nx = new SystemSettings.Extraction(x.browserPdfText(), trim(x.ocrMode()), trim(x.ocrLanguages()),
                trim(x.tesseractWorkerPath()), trim(x.tesseractCorePath()), trim(x.tesseractLangPath()));
        SystemSettings.BrowserLlm b = s.browserLlm();
        SystemSettings.BrowserLlm nb = new SystemSettings.BrowserLlm(b.enabled(), trim(b.defaultModel()), trim(b.configUrl()));
        return new SystemSettings(nl, ne, nb, nx, s.pipeline());
    }

    private void validate(SystemSettings s) {
        List<String> errors = new ArrayList<>();
        SystemSettings.Llm l = s.llm();
        if (!Set.of(SystemSettings.PROVIDER_MOCK, SystemSettings.PROVIDER_OPENAI).contains(l.provider())) errors.add("LLM sağlayıcısı geçersiz");
        if (!STRUCTURED_MODES.contains(l.structuredOutputMode())) errors.add("Structured output modu geçersiz");
        range(errors, "LLM zaman aşımı (sn)", l.timeoutSeconds(), 5, 900);
        range(errors, "LLM deneme sayısı", l.maxAttempts(), 1, 5);
        if (SystemSettings.PROVIDER_OPENAI.equals(l.provider())) {
            if (blank(l.baseUrl())) errors.add("LLM adresi zorunlu");
            if (blank(l.model())) errors.add("LLM model adı zorunlu");
            check(errors, "LLM adresi", l.baseUrl());
        }
        SystemSettings.Embedding e = s.embedding();
        if (e.enabled()) {
            if (blank(e.baseUrl()) || blank(e.model())) errors.add("Embedding açıkken adres ve model zorunlu");
            check(errors, "Embedding adresi", e.baseUrl());
        }
        SystemSettings.BrowserLlm b = s.browserLlm();
        if (b.enabled() && blank(b.defaultModel())) errors.add("Tarayıcı LLM varsayılan modeli zorunlu");
        if (b.configUrl().startsWith("http")) check(errors, "WebLLM model listesi adresi", b.configUrl());
        SystemSettings.Extraction x = s.extraction();
        if (!OCR_MODES.contains(x.ocrMode())) errors.add("OCR seçimi geçersiz");
        if ("BROWSER".equals(x.ocrMode()) && blank(x.ocrLanguages())) errors.add("OCR dili zorunlu (ör. tur+eng)");
        for (String p : List.of(x.tesseractWorkerPath(), x.tesseractCorePath(), x.tesseractLangPath())) {
            if (p.startsWith("http")) check(errors, "OCR dosya adresi", p);
        }
        SystemSettings.Pipeline p = s.pipeline();
        try {
            LlmMode mode = LlmMode.valueOf(p.defaultLlmMode());
            if (mode == LlmMode.BROWSER && !b.enabled()) errors.add("Varsayılan mod tarayıcı ise tarayıcı LLM açık olmalı");
        } catch (Exception ex) {
            errors.add("Varsayılan LLM modu geçersiz");
        }
        if (p.temperature() < 0 || p.temperature() > 1.5) errors.add("Temperature 0 ile 1,5 arasında olmalı");
        range(errors, "Model çıkarımı metin sınırı (sunucu)", p.modelExtractionMaxCharsServer(), 1_000, 1_000_000);
        range(errors, "Model çıkarımı metin sınırı (tarayıcı)", p.modelExtractionMaxCharsBrowser(), 1_000, 200_000);
        range(errors, "Tehdit grubu boyutu (sunucu)", p.threatBatchSizeServer(), 1, 50);
        range(errors, "Tehdit grubu boyutu (tarayıcı)", p.threatBatchSizeBrowser(), 1, 20);
        range(errors, "Aday başına kaynak", p.retrievalPerCandidate(), 1, 10);
        range(errors, "Kaynak türü başına üst sınır", p.retrievalMaxPerSource(), 1, 10);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(" · ", errors));
    }

    private void check(List<String> errors, String label, String url) {
        try {
            policy.check(label, url);
        } catch (IllegalArgumentException ex) {
            errors.add(ex.getMessage());
        }
    }

    private static void range(List<String> errors, String label, int v, int min, int max) {
        if (v < min || v > max) errors.add(label + " " + min + "–" + max + " arasında olmalı");
    }

    /** Değişen alanların okunur özeti; anahtar değerleri yazılmaz. */
    private static String diff(SystemSettings a, SystemSettings b) {
        List<String> out = new ArrayList<>();
        JsonNode ta = Json.MAPPER.valueToTree(a), tb = Json.MAPPER.valueToTree(b);
        diffNode("", ta, tb, out);
        return String.join("; ", out);
    }

    private static void diffNode(String path, JsonNode a, JsonNode b, List<String> out) {
        if (b.isObject()) {
            b.fieldNames().forEachRemaining(f -> diffNode(path.isEmpty() ? f : path + "." + f, a == null ? null : a.get(f), b.get(f), out));
            return;
        }
        if (Objects.equals(a, b)) return;
        boolean aEmpty = a == null || a.isNull() || (a.isTextual() && a.asText().isEmpty());
        boolean bEmpty = b == null || b.isNull() || (b.isTextual() && b.asText().isEmpty());
        if (aEmpty && bEmpty) return;
        if (path.endsWith("apiKey")) {
            out.add(path + (b.asText().isEmpty() ? " silindi" : " değiştirildi"));
        } else {
            out.add(path + ": " + (a == null || a.isNull() ? "–" : a.asText()) + " → " + (b.isNull() ? "–" : b.asText()));
        }
    }

    private static SecretStatus status(String key) {
        if (blank(key)) return new SecretStatus(false, "");
        String k = key.strip();
        return new SecretStatus(true, "••••" + (k.length() > 8 ? k.substring(k.length() - 4) : ""));
    }

    private static SystemSettings.Llm withKey(SystemSettings.Llm l, String key) {
        return new SystemSettings.Llm(l.provider(), l.baseUrl(), key, l.model(), l.timeoutSeconds(), l.structuredOutputMode(),
                l.maxAttempts(), l.taskModels());
    }

    private static SystemSettings.Embedding withKey(SystemSettings.Embedding e, String key) {
        return new SystemSettings.Embedding(e.enabled(), e.baseUrl(), key, e.model());
    }

    private static String trim(String s) { return s == null ? "" : s.strip(); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String message(Exception ex) {
        String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (ex instanceof java.net.ConnectException || m.contains("ConnectException")) return "Bağlanılamadı: adres veya ağ erişimi kontrol edilmeli";
        if (ex instanceof java.net.http.HttpTimeoutException) return "Zaman aşımı";
        return m.length() > 400 ? m.substring(0, 400) + "…" : m;
    }
}
