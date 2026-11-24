package com.bank.tm.core.settings;

import com.bank.tm.core.llm.LlmClient;
import com.bank.tm.core.llm.LlmRequest;
import com.bank.tm.core.llm.LlmResponse;
import com.bank.tm.core.llm.LlmTask;

import java.util.Map;

/**
 * Çalışırken değiştirilebilen LLM istemcisi. Sistem parametreleri kaydedildiğinde yeni istemciler kurulur ve
 * buraya atanır; devam eden çağrılar eski istemciyle tamamlanır, sonraki çağrılar yenisini kullanır.
 * Adım bazında model tanımlıysa (ör. tehdit üretimi için daha büyük model) o adımın istemcisi seçilir.
 */
public final class SwitchableLlmClient implements LlmClient {

    private record Clients(LlmClient fallback, Map<LlmTask, LlmClient> perTask) {
    }

    private volatile Clients clients;

    public SwitchableLlmClient(LlmClient initial) {
        this.clients = new Clients(initial, Map.of());
    }

    public void replace(LlmClient fallback, Map<LlmTask, LlmClient> perTask) {
        this.clients = new Clients(fallback, Map.copyOf(perTask));
    }

    public LlmClient forTask(LlmTask task) {
        Clients c = clients;
        return task == null ? c.fallback() : c.perTask().getOrDefault(task, c.fallback());
    }

    @Override
    public String name() { return clients.fallback().name(); }

    @Override
    public String model() { return clients.fallback().model(); }

    @Override
    public LlmResponse complete(LlmRequest request) throws Exception {
        return forTask(request.task()).complete(request);
    }
}
