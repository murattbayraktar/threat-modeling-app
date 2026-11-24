package com.bank.tm.core;

import com.bank.tm.core.llm.LlmTask;
import com.bank.tm.core.llm.MockLlmClient;
import com.bank.tm.core.settings.EndpointPolicy;
import com.bank.tm.core.settings.SecretCipher;
import com.bank.tm.core.settings.SettingsService;
import com.bank.tm.core.settings.SwitchableEmbeddingProvider;
import com.bank.tm.core.settings.SwitchableLlmClient;
import com.bank.tm.core.settings.SystemSettings;
import com.bank.tm.core.store.InMemoryReviewStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsServiceTest {

    private final EndpointPolicy policy = new EndpointPolicy(true, List.of(".bank.local"));

    private static SystemSettings openAi(SystemSettings base, String url, String key, String model, Map<String, String> taskModels) {
        SystemSettings.Llm l = base.llm();
        return base.withLlm(new SystemSettings.Llm(SystemSettings.PROVIDER_OPENAI, url, key, model, 60, "JSON_SCHEMA", 3, taskModels));
    }

    @Test
    void cipherRoundTripAndPlainFallback() {
        SecretCipher c = new SecretCipher("parola-123");
        String enc = c.encrypt("sk-gizli-anahtar");
        assertTrue(enc.startsWith("enc:v1:"));
        assertFalse(enc.contains("gizli"));
        assertEquals("sk-gizli-anahtar", c.decrypt(enc));
        SecretCipher none = new SecretCipher(null);
        assertFalse(none.enabled());
        assertEquals("plain:abc", none.encrypt("abc"));
        assertEquals("abc", none.decrypt("plain:abc"));
    }

    @Test
    void endpointPolicyAllowsOnlyInternalHosts() {
        assertTrue(policy.isInternal("10.20.30.40"));
        assertTrue(policy.isInternal("192.168.1.5"));
        assertTrue(policy.isInternal("localhost"));
        assertTrue(policy.isInternal("genai-gw"));
        assertTrue(policy.isInternal("genai.bank.local"));
        assertFalse(policy.isInternal("api.openai.com"));
        assertFalse(policy.isInternal("8.8.8.8"));
        assertThrows(IllegalArgumentException.class, () -> policy.check("LLM", "https://api.openai.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> policy.check("LLM", "ftp://genai.bank.local"));
        policy.check("LLM", "https://genai.bank.local/v1");
    }

    @Test
    void updateEncryptsMasksKeepsAndClearsSecrets() {
        InMemoryReviewStore store = new InMemoryReviewStore();
        SwitchableLlmClient llm = new SwitchableLlmClient(new MockLlmClient());
        SettingsService svc = new SettingsService(store, SystemSettings.defaults(), new SecretCipher("k"), policy, llm,
                new SwitchableEmbeddingProvider(null), true);

        SettingsService.View v = svc.update(openAi(svc.current(), "https://genai.bank.local/v1", "sk-1234567890abcd", "model-a",
                Map.of("THREAT_GENERATION", "model-b")), "admin");
        assertEquals("", v.settings().llm().apiKey(), "anahtar ekrana dönmemeli");
        assertTrue(v.secrets().get("llm.apiKey").set());
        assertEquals("••••abcd", v.secrets().get("llm.apiKey").hint());
        assertTrue(store.getSetting(SettingsService.KEY).orElseThrow().contains("enc:v1:"));
        assertFalse(store.getSetting(SettingsService.KEY).orElseThrow().contains("sk-1234567890abcd"));

        // İstemciler yeniden kuruldu; adım bazında model seçildi
        assertEquals("openai-compatible", llm.name());
        assertEquals("model-a", llm.forTask(LlmTask.MODEL_EXTRACTION).model());
        assertEquals("model-b", llm.forTask(LlmTask.THREAT_GENERATION).model());

        // Boş anahtar = koru
        svc.update(openAi(v.settings(), "https://genai.bank.local/v1", "", "model-c", Map.of()), "admin");
        assertEquals("sk-1234567890abcd", svc.current().llm().apiKey());
        assertEquals("model-c", llm.forTask(LlmTask.THREAT_GENERATION).model());

        // Yeniden açılışta veritabanından çözülerek yüklenir
        SettingsService reopened = new SettingsService(store, SystemSettings.defaults(), new SecretCipher("k"), policy,
                new SwitchableLlmClient(new MockLlmClient()), new SwitchableEmbeddingProvider(null), true);
        assertEquals("sk-1234567890abcd", reopened.current().llm().apiKey());
        assertEquals("veritabanı", reopened.source());

        // CLEAR = sil; geçmiş tutulur, anahtar değeri geçmişe yazılmaz
        svc.update(openAi(v.settings(), "https://genai.bank.local/v1", SettingsService.CLEAR, "model-c", Map.of()), "admin");
        assertEquals("", svc.current().llm().apiKey());
        List<com.bank.tm.core.store.ReviewStore.SettingChange> h = svc.history(10);
        assertTrue(h.size() >= 3);
        assertTrue(h.get(0).summary().contains("llm.apiKey silindi"));
        assertFalse(h.stream().anyMatch(c -> c.summary().contains("sk-1234567890abcd")));
    }

    @Test
    void rejectsCloudEndpointAndInvalidValues() {
        SettingsService svc = new SettingsService(new InMemoryReviewStore(), SystemSettings.defaults(), new SecretCipher(null), policy,
                new SwitchableLlmClient(new MockLlmClient()), new SwitchableEmbeddingProvider(null), true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.update(openAi(svc.current(), "https://api.openai.com/v1", "x", "gpt", Map.of()), "admin"));
        assertTrue(e.getMessage().contains("kurum içi"));
        SystemSettings s = svc.current();
        SystemSettings.Pipeline p = s.pipeline();
        SystemSettings bad = new SystemSettings(s.llm(), s.embedding(), s.browserLlm(), s.extraction(),
                new SystemSettings.Pipeline(p.defaultLlmMode(), 3.0, p.modelExtractionMaxCharsServer(), p.modelExtractionMaxCharsBrowser(),
                        0, p.threatBatchSizeBrowser(), p.retrievalPerCandidate(), p.retrievalMaxPerSource()));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> svc.update(bad, "admin"));
        assertTrue(e2.getMessage().contains("Temperature"));
        assertTrue(e2.getMessage().contains("Tehdit grubu boyutu"));
    }

    @Test
    void pipelineSettingsTakeEffectWithoutRestart() {
        var core = Fixtures.core();
        assertEquals(12, core.settings().threatBatchSizeServer());
        SystemSettings s = core.systemSettings.current();
        SystemSettings.Pipeline p = s.pipeline();
        core.systemSettings.update(new SystemSettings(s.llm(), s.embedding(), s.browserLlm(), s.extraction(),
                new SystemSettings.Pipeline(p.defaultLlmMode(), 0.2, p.modelExtractionMaxCharsServer(), p.modelExtractionMaxCharsBrowser(),
                        6, p.threatBatchSizeBrowser(), 3, p.retrievalMaxPerSource())), "admin");
        assertEquals(6, core.settings().threatBatchSizeServer());
        assertEquals(3, core.settings().retrievalPerCandidate());
    }
}
