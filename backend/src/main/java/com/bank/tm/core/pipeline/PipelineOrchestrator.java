package com.bank.tm.core.pipeline;

import com.bank.tm.core.domain.PipelineStep;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewStatus;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.domain.StepStatus;
import com.bank.tm.core.llm.LlmGateway;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.util.Clock;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Akışın orkestratörü. Adımları sırayla çalıştırır; kullanıcı onayı veya tarayıcı LLM çıktısı beklenen adımda
 * durur. Tetiklendiğinde (onay, LLM sonucu, yeniden dene) ilk tamamlanmamış adımdan devam eder.
 * Aynı inceleme için aynı anda tek çalıştırma olur.
 */
public class PipelineOrchestrator {

    private static final Logger LOG = Logger.getLogger(PipelineOrchestrator.class.getName());

    private final ReviewStore store;
    private final Map<Stage, StageHandler> handlers = new EnumMap<>(Stage.class);
    private final Executor executor;
    private final LlmGateway gateway;
    private final Map<String, RunState> states = new ConcurrentHashMap<>();

    private static final class RunState {
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicBoolean requested = new AtomicBoolean(false);
    }

    public PipelineOrchestrator(ReviewStore store, List<StageHandler> stageHandlers, Executor executor,
                                LlmGateway gateway) {
        this.store = store;
        this.executor = executor;
        this.gateway = gateway;
        stageHandlers.forEach(h -> handlers.put(h.stage(), h));
        for (Stage s : Stage.values()) {
            if (!handlers.containsKey(s)) throw new IllegalStateException("Adım için handler yok: " + s);
        }
    }

    public void initialize(String reviewId) {
        for (Stage s : Stage.values()) store.upsertStep(PipelineStep.pending(reviewId, s));
    }

    /** Akışı (arka planda) ilerletir. */
    public void trigger(String reviewId) {
        RunState st = states.computeIfAbsent(reviewId, k -> new RunState());
        st.requested.set(true);
        if (st.running.compareAndSet(false, true)) {
            executor.execute(() -> loop(reviewId, st));
        }
    }

    public boolean isRunning(String reviewId) {
        RunState st = states.get(reviewId);
        return st != null && st.running.get();
    }

    /** Belirtilen adım ve sonrasını sıfırlar (ara çıktılar ve LLM çağrıları geçersiz olur). */
    public void resetFrom(String reviewId, Stage from) {
        for (PipelineStep s : store.listSteps(reviewId)) {
            if (s.stage().isAfterOrEqual(from)) store.upsertStep(PipelineStep.pending(reviewId, s.stage()));
        }
        store.deleteArtifactsFrom(reviewId, from);
        gateway.supersedeFrom(reviewId, from);
        if (Stage.EXPERT_REVIEW.isAfterOrEqual(from)) {
            store.findReview(reviewId).ifPresent(r -> store.updateReview(r.withExpertReviewCompleted(false, Clock.now())));
        }
        if (!from.isAfterOrEqual(Stage.RISK_ASSESSMENT)) store.replaceThreats(reviewId, List.of());
        refreshStatus(reviewId);
    }

    private void loop(String reviewId, RunState st) {
        try {
            while (st.requested.getAndSet(false)) runOnce(reviewId);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Akış hatası: " + reviewId, e);
        } finally {
            st.running.set(false);
            if (st.requested.get()) trigger(reviewId);
        }
    }

    void runOnce(String reviewId) {
        while (true) {
            Review review = store.findReview(reviewId).orElseThrow();
            PipelineStep step = store.listSteps(reviewId).stream().filter(s -> s.status() != StepStatus.DONE)
                    .findFirst().orElse(null);
            if (step == null) {
                refreshStatus(reviewId);
                return;
            }
            StageHandler handler = handlers.get(step.stage());
            var started = step.startedAt() != null ? step.startedAt() : Clock.now();
            if (step.stage().actor() != Stage.Actor.ANALYST) {
                store.upsertStep(new PipelineStep(reviewId, step.stage(), StepStatus.RUNNING, "Çalışıyor…",
                        step.metrics(), started, null));
                refreshStatus(reviewId);
            }
            StageResult result;
            try {
                result = handler.run(review);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Adım başarısız: " + step.stage() + " / " + reviewId, e);
                store.upsertStep(new PipelineStep(reviewId, step.stage(), StepStatus.FAILED,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), step.metrics(), started,
                        Clock.now()));
                refreshStatus(reviewId);
                return;
            }
            switch (result.outcome()) {
                case DONE -> store.upsertStep(new PipelineStep(reviewId, step.stage(), StepStatus.DONE,
                        result.message(), result.metrics(), started, Clock.now()));
                case WAIT_USER -> store.upsertStep(new PipelineStep(reviewId, step.stage(), StepStatus.WAITING_USER,
                        result.message(), result.metrics(), started, null));
                case WAIT_LLM -> store.upsertStep(new PipelineStep(reviewId, step.stage(), StepStatus.WAITING_LLM,
                        result.message(), result.metrics(), started, null));
            }
            refreshStatus(reviewId);
            if (result.outcome() != StageResult.Outcome.DONE) return;
        }
    }

    public void refreshStatus(String reviewId) {
        Review r = store.findReview(reviewId).orElse(null);
        if (r == null) return;
        List<PipelineStep> steps = store.listSteps(reviewId);
        PipelineStep current = steps.stream().filter(s -> s.status() != StepStatus.DONE).findFirst().orElse(null);
        ReviewStatus status;
        Stage stage;
        if (current == null) {
            status = ReviewStatus.COMPLETED;
            stage = Stage.REPORT;
        } else {
            stage = current.stage();
            status = switch (current.status()) {
                case FAILED -> ReviewStatus.FAILED;
                case WAITING_LLM -> ReviewStatus.WAITING_LLM;
                case WAITING_USER -> stage == Stage.MODEL_REVIEW ? ReviewStatus.WAITING_MODEL_APPROVAL
                        : ReviewStatus.IN_EXPERT_REVIEW;
                default -> ReviewStatus.IN_PROGRESS;
            };
        }
        if (r.status() != status || r.currentStage() != stage) store.updateReview(r.withState(status, stage, Clock.now()));
    }
}
