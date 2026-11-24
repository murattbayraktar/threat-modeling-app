package com.bank.tm.core.llm;

import com.bank.tm.core.domain.LlmCall;
import com.bank.tm.core.domain.LlmCallStatus;
import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.util.Clock;
import com.bank.tm.core.util.Ids;
import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Tüm LLM çağrılarının geçtiği tek nokta:
 * - Her çağrıyı (istek, ham çıktı, hatalar, süre) kaydeder.
 * - Çıktıyı JSON Schema ile doğrular; uymazsa hataları bildirerek bir kez daha dener.
 * - SERVER modunda sunucu istemcisini çağırır; BROWSER modunda isteği "PENDING" olarak bırakır ve tarayıcının
 *   çıktıyı göndermesini bekler (akış WAITING_LLM durumunda durur).
 * - Aynı (adım, anahtar) için tamamlanmış çağrı varsa yeniden çağırmaz (yeniden çalıştırmada idempotent).
 */
public class LlmGateway {

    public sealed interface Outcome permits Done, Deferred {
    }

    public record Done(JsonNode json, LlmCall call) implements Outcome {
    }

    public record Deferred(LlmCall call) implements Outcome {
    }

    public static class LlmFailure extends RuntimeException {
        public LlmFailure(String message) {
            super(message);
        }
    }

    private final ReviewStore store;
    private final LlmClient serverClient;
    private volatile int maxAttempts;

    public LlmGateway(ReviewStore store, LlmClient serverClient, int maxAttempts) {
        this.store = store;
        this.serverClient = serverClient;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public LlmClient serverClient() { return serverClient; }

    /** Sistem parametreleri ekranından değiştirilebilir. */
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.max(1, maxAttempts); }

    public int maxAttempts() { return maxAttempts; }

    /** Adım bazında model seçilmişse kayıtta o model görünsün. */
    private LlmClient clientFor(LlmRequest req) {
        return serverClient instanceof com.bank.tm.core.settings.SwitchableLlmClient s ? s.forTask(req.task()) : serverClient;
    }

    public Outcome call(String reviewId, Stage stage, String callKey, LlmMode mode, LlmRequest request) {
        List<LlmCall> previous = active(reviewId, stage, callKey);
        LlmCall last = previous.isEmpty() ? null : previous.get(previous.size() - 1);
        LlmRequest next = request;
        if (last != null) {
            switch (last.status()) {
                case COMPLETED -> {
                    return new Done(StructuredOutput.parse(last.rawOutput(), request.schema()).json(), last);
                }
                case PENDING -> {
                    return new Deferred(last);
                }
                case SUBMITTED -> {
                    StructuredOutput.Result r = StructuredOutput.parse(last.rawOutput(), request.schema());
                    if (r.valid()) {
                        LlmCall done = last.withResult(LlmCallStatus.COMPLETED, last.rawOutput(), null, List.of(),
                                last.durationMs(), Clock.now());
                        store.updateLlmCall(done);
                        return new Done(r.json(), done);
                    }
                    LlmCall invalid = last.withResult(LlmCallStatus.INVALID, last.rawOutput(), null, r.errors(),
                            last.durationMs(), Clock.now());
                    store.updateLlmCall(invalid);
                    last = invalid;
                }
                default -> { }
            }
            if (last.attempt() >= maxAttempts) {
                throw new LlmFailure("LLM çıktısı " + last.attempt() + " denemede de geçerli olmadı: "
                        + String.join("; ", last.errors().subList(0, Math.min(3, last.errors().size()))));
            }
            if (last.status() == LlmCallStatus.INVALID) next = request.withRepair(last.rawOutput(), last.errors());
        }
        int attempt = last == null ? 1 : last.attempt() + 1;

        if (mode == LlmMode.BROWSER) {
            LlmCall pending = new LlmCall(Ids.uuid(), reviewId, stage, callKey, attempt, "browser", null,
                    LlmCallStatus.PENDING, next, null, List.of(), null, Clock.now(), null);
            store.insertLlmCall(pending);
            return new Deferred(pending);
        }

        while (true) {
            LlmClient client = clientFor(next);
            LlmCall call = new LlmCall(Ids.uuid(), reviewId, stage, callKey, attempt, "server:" + client.name(),
                    client.model(), LlmCallStatus.FAILED, next, null, List.of(), null, Clock.now(), null);
            try {
                LlmResponse res = client.complete(next);
                StructuredOutput.Result r = StructuredOutput.parse(res.text(), request.schema());
                if (r.valid()) {
                    LlmCall done = call.withResult(LlmCallStatus.COMPLETED, res.text(), res.model(), List.of(),
                            res.durationMs(), Clock.now());
                    store.insertLlmCall(done);
                    return new Done(r.json(), done);
                }
                store.insertLlmCall(call.withResult(LlmCallStatus.INVALID, res.text(), res.model(), r.errors(),
                        res.durationMs(), Clock.now()));
                next = request.withRepair(res.text(), r.errors());
                if (attempt >= maxAttempts) {
                    throw new LlmFailure("LLM çıktısı şemaya uymadı: " + String.join("; ",
                            r.errors().subList(0, Math.min(3, r.errors().size()))));
                }
            } catch (LlmFailure e) {
                throw e;
            } catch (Exception e) {
                store.insertLlmCall(call.withResult(LlmCallStatus.FAILED, null, null, List.of(String.valueOf(e.getMessage())),
                        null, Clock.now()));
                if (attempt >= maxAttempts) throw new LlmFailure("LLM çağrısı başarısız: " + e.getMessage());
            }
            attempt++;
        }
    }

    /** Tarayıcının ürettiği çıktıyı kaydeder; doğrulama akış devam ettiğinde yapılır. */
    public LlmCall submitBrowserResult(String callId, String rawOutput, String model, Long durationMs) {
        LlmCall call = store.findLlmCall(callId).orElseThrow(() -> new IllegalArgumentException("LLM görevi bulunamadı: " + callId));
        if (call.status() != LlmCallStatus.PENDING) {
            throw new IllegalStateException("Görev beklemede değil (durum: " + call.status() + ")");
        }
        LlmCall submitted = call.withResult(LlmCallStatus.SUBMITTED, rawOutput, model, List.of(), durationMs, Clock.now());
        store.updateLlmCall(submitted);
        return submitted;
    }

    /** Akış bir adımdan yeniden başlatıldığında o adım ve sonrasındaki çağrılar kullanılmaz hale gelir. */
    public void supersedeFrom(String reviewId, Stage stage) {
        for (LlmCall c : store.listLlmCalls(reviewId)) {
            if (c.stage().isAfterOrEqual(stage) && c.status() != LlmCallStatus.SUPERSEDED) {
                store.updateLlmCall(c.withStatus(LlmCallStatus.SUPERSEDED));
            }
        }
    }

    public Optional<LlmCall> pendingFor(String reviewId) {
        return store.listLlmCalls(reviewId).stream().filter(c -> c.status() == LlmCallStatus.PENDING).findFirst();
    }

    private List<LlmCall> active(String reviewId, Stage stage, String key) {
        return store.listLlmCalls(reviewId).stream()
                .filter(c -> c.stage() == stage && c.callKey().equals(key) && c.status() != LlmCallStatus.SUPERSEDED)
                .sorted(Comparator.comparingInt(LlmCall::attempt).thenComparing(LlmCall::createdAt))
                .toList();
    }

    static String debug(Object o) { return Json.write(o); }
}
