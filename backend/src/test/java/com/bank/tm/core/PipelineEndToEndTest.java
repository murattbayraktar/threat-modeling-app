package com.bank.tm.core;

import com.bank.tm.core.domain.Decision;
import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewStatus;
import com.bank.tm.core.llm.LlmRequest;
import com.bank.tm.core.llm.MockLlmClient;
import com.bank.tm.core.service.EvaluationService;
import com.bank.tm.core.service.ReportBuilder;
import com.bank.tm.core.service.ReviewService;
import com.bank.tm.core.service.TmCore;
import com.bank.tm.core.service.Views;
import com.bank.tm.core.store.InMemoryReviewStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineEndToEndTest {

    @Test
    void serverModeFullFlowOnInvoiceSample() {
        TmCore core = Fixtures.core();
        Review r = core.reviews.createFromSample("tedarikci-fatura-entegrasyonu", "analist", LlmMode.SERVER, null);

        Views.ReviewDetail d = core.reviews.detail(r.id());
        assertEquals(ReviewStatus.WAITING_MODEL_APPROVAL, d.review().status(), stepMessages(d));
        assertEquals(Integer.valueOf(1), d.modelVersion());
        assertTrue(core.reviews.model(r.id()).editable());

        // Tehdit adımları onaydan önce çalışmamalı
        assertTrue(core.reviews.threats(r.id()).isEmpty());
        core.reviews.approveModel(r.id(), "analist");

        d = core.reviews.detail(r.id());
        assertEquals(ReviewStatus.IN_EXPERT_REVIEW, d.review().status(), stepMessages(d));
        List<Views.ThreatView> threats = core.reviews.threats(r.id());
        assertTrue(threats.size() >= 15, "Yeterli tehdit üretilmeli: " + threats.size());
        assertTrue(threats.stream().allMatch(t -> t.system() != null && t.level() != null));
        assertTrue(threats.stream().filter(t -> !t.sources().isEmpty()).count() > threats.size() / 2,
                "Tehditlerin çoğu RAG kaynaklarına bağlanmalı");
        assertTrue(threats.stream().anyMatch(t -> t.level().equals("CRITICAL")));

        EvaluationService.Evaluation ev = core.evaluation.evaluate(r.id());
        assertTrue(ev.recall() >= 0.8, "Recall düşük: " + ev.details());

        // Analist tamamlamadan önce tüm kararları vermeli
        assertThrows(RuntimeException.class, () -> core.reviews.completeExpertReview(r.id(), "analist"));
        Views.ThreatView first = threats.get(0);
        assertThrows(IllegalArgumentException.class, () -> core.reviews.updateThreat(r.id(), first.id(),
                new ReviewService.ThreatUpdate(null, null, null, 5, 5, null, null, null, null), "analist"));
        Views.ThreatView overridden = core.reviews.updateThreat(r.id(), first.id(),
                new ReviewService.ThreatUpdate(Decision.ACCEPTED, null, null, 2, 3, null, "Telafi edici kontrol var", null, "not"),
                "analist");
        assertEquals(6, overridden.score());
        assertEquals("MEDIUM", overridden.level());
        assertNotNull(overridden.system(), "Sistem hesabı ayrı saklanmalı");
        for (int i = 1; i < threats.size(); i++) {
            core.reviews.updateThreat(r.id(), threats.get(i).id(), new ReviewService.ThreatUpdate(
                    i == 1 ? Decision.FALSE_POSITIVE : Decision.ACCEPTED, null, null, null, null, null, null, null, null),
                    "analist");
        }
        core.reviews.completeExpertReview(r.id(), "analist");
        d = core.reviews.detail(r.id());
        assertEquals(ReviewStatus.COMPLETED, d.review().status());

        ReportBuilder.Report report = core.reviews.report(r.id());
        assertEquals(threats.size() - 1, report.decisions().get("ACCEPTED"));
        assertEquals(1, report.decisions().get("FALSE_POSITIVE"));
        int matrixTotal = 0;
        for (int[] row : report.riskMatrix()) for (int v : row) matrixTotal += v;
        assertEquals(threats.size() - 1, matrixTotal);
        assertFalse(report.actionPlan().isEmpty());
        assertEquals(1L, core.reviews.threatEvents(r.id()).stream().filter(e -> e.threatId().equals(first.id())).count(),
                "Geçersiz istek kaydedilmez; kabul + override tek olay");
        assertFalse(core.reviews.llmCalls(r.id()).isEmpty());

        Map<String, Object> metrics = core.metrics.compute();
        assertEquals(1L, metrics.get("completedReviews"));
    }

    @Test
    void browserModeWaitsForClientAndRepairsInvalidOutput() throws Exception {
        TmCore core = Fixtures.core(Fixtures.browserSettings());
        MockLlmClient browserSimulator = new MockLlmClient();
        Review r = core.reviews.createFromSample("kurumsal-talimat-api", "analist", LlmMode.BROWSER, null);
        assertEquals(ReviewStatus.WAITING_LLM, core.reviews.detail(r.id()).review().status());

        List<Views.LlmTaskView> tasks = core.reviews.pendingLlmTasks(r.id());
        assertEquals(1, tasks.size());
        assertEquals("MODEL_EXTRACTION", tasks.get(0).stage());
        assertNotNull(tasks.get(0).schema());

        // Geçersiz çıktı -> doğrulama hatası -> düzeltme istemiyle ikinci deneme
        core.reviews.submitLlmResult(tasks.get(0).callId(), "bu bir json değil", "test", 10L, "analist");
        List<Views.LlmTaskView> retry = core.reviews.pendingLlmTasks(r.id());
        assertEquals(1, retry.size());
        assertEquals(2, retry.get(0).attempt());
        assertTrue(retry.get(0).userPrompt().contains("ŞEMAYA UYMADI"));

        runBrowserTasks(core, browserSimulator, r.id());
        assertEquals(ReviewStatus.WAITING_MODEL_APPROVAL, core.reviews.detail(r.id()).review().status());
        core.reviews.approveModel(r.id(), "analist");
        int rounds = runBrowserTasks(core, browserSimulator, r.id());
        assertTrue(rounds >= 2, "Tarayıcı modunda tehditler küçük partilerle üretilmeli");
        assertEquals(ReviewStatus.IN_EXPERT_REVIEW, core.reviews.detail(r.id()).review().status());
        EvaluationService.Evaluation ev = core.evaluation.evaluate(r.id());
        assertTrue(ev.recall() >= 0.8, "Recall düşük: " + ev.details());
    }

    @Test
    void rejectAndBackToModelResetDownstream() {
        TmCore core = Fixtures.core();
        Review r = core.reviews.createFromSample("mobil-sube-randevu", "analist", LlmMode.SERVER, null);
        core.reviews.rejectModel(r.id(), "SMS servisini dış sistem olarak ele al", "analist");
        assertEquals(Integer.valueOf(2), core.reviews.detail(r.id()).modelVersion());
        core.reviews.approveModel(r.id(), "analist");
        assertFalse(core.reviews.threats(r.id()).isEmpty());
        core.reviews.backToModel(r.id(), "analist");
        assertTrue(core.reviews.threats(r.id()).isEmpty());
        assertEquals(ReviewStatus.WAITING_MODEL_APPROVAL, core.reviews.detail(r.id()).review().status());
        assertTrue(core.reviews.llmCalls(r.id()).stream().anyMatch(c -> c.status().equals("SUPERSEDED")));
    }

    private static int runBrowserTasks(TmCore core, MockLlmClient sim, String reviewId) throws Exception {
        int rounds = 0;
        while (true) {
            List<Views.LlmTaskView> tasks = core.reviews.pendingLlmTasks(reviewId);
            if (tasks.isEmpty()) return rounds;
            Views.LlmTaskView t = tasks.get(0);
            var call = ((InMemoryReviewStore) core.store).findLlmCall(t.callId()).orElseThrow();
            LlmRequest req = call.request();
            core.reviews.submitLlmResult(t.callId(), sim.complete(req).text(), "web-llm-sim", 5L, "analist");
            rounds++;
            if (rounds > 20) throw new AssertionError("Sonsuz döngü");
        }
    }

    private static String stepMessages(Views.ReviewDetail d) {
        StringBuilder sb = new StringBuilder();
        d.steps().forEach(s -> sb.append(s.stage()).append('=').append(s.status()).append(' ').append(s.message()).append('\n'));
        return sb.toString();
    }
}
