package com.bank.tm.app.web;

import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.AuditEvent;
import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ThreatEvent;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.model.ThreatCandidate;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.service.EvaluationService;
import com.bank.tm.core.service.ReportBuilder;
import com.bank.tm.core.service.ReviewService;
import com.bank.tm.core.service.TmCore;
import com.bank.tm.core.service.Views;
import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    /** Çok parçalı yüklemedeki "meta" alanı. */
    public record CreateMeta(String title, String businessUnit, String projectType, String dataClassification,
                             String owner, String analyst, LlmMode llmMode, Map<String, Object> answers) {
    }

    /** Dosya adı → tarayıcıda çıkarılmış metin. */
    public record ClientText(String text, String method) {
    }

    public record FromSample(LlmMode llmMode, Map<String, String> clientTexts) {
    }

    public record Note(String note) {
    }

    public record ModeChange(LlmMode mode) {
    }

    private static final String USER = "X-User";

    private final ReviewService reviews;
    private final TmCore core;

    public ReviewController(ReviewService reviews, TmCore core) {
        this.reviews = reviews;
        this.core = core;
    }

    @GetMapping
    public List<Views.ReviewSummary> list() {
        return reviews.list();
    }

    @PostMapping(consumes = "multipart/form-data")
    public Review create(@RequestParam("meta") String metaJson,
                         @RequestParam("files") List<MultipartFile> files,
                         @RequestParam(value = "clientTexts", required = false) String clientTextsJson,
                         @RequestHeader(value = USER, required = false) String user) throws IOException {
        CreateMeta meta = Json.read(metaJson, CreateMeta.class);
        Map<String, ClientText> clientTexts = clientTextsJson == null || clientTextsJson.isBlank() ? Map.of()
                : Json.read(clientTextsJson, new TypeReference<Map<String, ClientText>>() { });
        List<ReviewService.UploadedFile> uploaded = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() == null ? "dosya" : f.getOriginalFilename();
            ClientText ct = clientTexts.get(name);
            uploaded.add(new ReviewService.UploadedFile(name, f.getContentType(), f.getBytes(),
                    ct == null ? null : ct.text(), ct == null ? null : ct.method()));
        }
        String u = CurrentUser.of(user);
        return reviews.create(new ReviewService.CreateReview(meta.title(), meta.businessUnit(), meta.projectType(),
                meta.dataClassification(), meta.owner(), meta.analyst() == null ? u : meta.analyst(), meta.llmMode(),
                meta.answers()), uploaded, u, null);
    }

    @PostMapping("/from-sample/{name}")
    public Review fromSample(@PathVariable("name") String name, @RequestBody(required = false) FromSample body,
                             @RequestHeader(value = USER, required = false) String user) {
        return reviews.createFromSample(name, CurrentUser.of(user), body == null ? null : body.llmMode(),
                body == null ? null : body.clientTexts());
    }

    @GetMapping("/{id}")
    public Views.ReviewDetail detail(@PathVariable("id") String id) {
        return reviews.detail(id);
    }

    @GetMapping("/{id}/documents")
    public List<ParsedDocument> documents(@PathVariable("id") String id) {
        return reviews.parsedDocuments(id);
    }

    // ---------------------------------------------------------------- model

    @GetMapping("/{id}/model")
    public Views.ModelView model(@PathVariable("id") String id) {
        return reviews.model(id);
    }

    @PutMapping("/{id}/model")
    public ModelVersion updateModel(@PathVariable("id") String id, @RequestBody SystemModel model,
                                    @RequestHeader(value = USER, required = false) String user) {
        return reviews.updateModel(id, model, CurrentUser.of(user));
    }

    @PostMapping("/{id}/model/approve")
    public ModelVersion approve(@PathVariable("id") String id, @RequestHeader(value = USER, required = false) String user) {
        return reviews.approveModel(id, CurrentUser.of(user));
    }

    @PostMapping("/{id}/model/reject")
    public Map<String, String> reject(@PathVariable("id") String id, @RequestBody(required = false) Note note,
                                      @RequestHeader(value = USER, required = false) String user) {
        reviews.rejectModel(id, note == null ? null : note.note(), CurrentUser.of(user));
        return Map.of("status", "ok");
    }

    @PostMapping("/{id}/back-to-model")
    public ModelVersion backToModel(@PathVariable("id") String id, @RequestHeader(value = USER, required = false) String user) {
        return reviews.backToModel(id, CurrentUser.of(user));
    }

    // ---------------------------------------------------------------- ara çıktılar

    @GetMapping("/{id}/candidates")
    public List<ThreatCandidate> candidates(@PathVariable("id") String id) {
        return reviews.candidates(id);
    }

    @GetMapping("/{id}/retrieval")
    public Map<String, Object> retrieval(@PathVariable("id") String id) {
        return reviews.retrieval(id);
    }

    @GetMapping("/{id}/artifacts/{kind}")
    public Map<String, Object> artifact(@PathVariable("id") String id, @PathVariable("kind") ArtifactKind kind) {
        return reviews.artifact(id, kind);
    }

    // ---------------------------------------------------------------- tehditler

    @GetMapping("/{id}/threats")
    public List<Views.ThreatView> threats(@PathVariable("id") String id) {
        return reviews.threats(id);
    }

    @PatchMapping("/{id}/threats/{threatId}")
    public Views.ThreatView updateThreat(@PathVariable("id") String id, @PathVariable("threatId") String threatId,
                                         @RequestBody ReviewService.ThreatUpdate update,
                                         @RequestHeader(value = USER, required = false) String user) {
        return reviews.updateThreat(id, threatId, update, CurrentUser.of(user));
    }

    @PostMapping("/{id}/threats")
    public Views.ThreatView addThreat(@PathVariable("id") String id, @RequestBody ReviewService.NewThreat threat,
                                      @RequestHeader(value = USER, required = false) String user) {
        return reviews.addThreat(id, threat, CurrentUser.of(user));
    }

    @PostMapping("/{id}/expert-review/complete")
    public Map<String, String> complete(@PathVariable("id") String id, @RequestHeader(value = USER, required = false) String user) {
        reviews.completeExpertReview(id, CurrentUser.of(user));
        return Map.of("status", "ok");
    }

    @PostMapping("/{id}/expert-review/reopen")
    public Map<String, String> reopen(@PathVariable("id") String id, @RequestHeader(value = USER, required = false) String user) {
        reviews.reopenExpertReview(id, CurrentUser.of(user));
        return Map.of("status", "ok");
    }

    // ---------------------------------------------------------------- akış

    @PostMapping("/{id}/retry")
    public Map<String, String> retry(@PathVariable("id") String id, @RequestHeader(value = USER, required = false) String user) {
        reviews.retry(id, CurrentUser.of(user));
        return Map.of("status", "ok");
    }

    @PutMapping("/{id}/llm-mode")
    public Review llmMode(@PathVariable("id") String id, @RequestBody ModeChange body,
                          @RequestHeader(value = USER, required = false) String user) {
        return reviews.setLlmMode(id, body.mode(), CurrentUser.of(user));
    }

    // ---------------------------------------------------------------- rapor ve izlenebilirlik

    @GetMapping("/{id}/report")
    public ReportBuilder.Report report(@PathVariable("id") String id) {
        return reviews.report(id);
    }

    @GetMapping("/{id}/evaluation")
    public EvaluationService.Evaluation evaluation(@PathVariable("id") String id) {
        return core.evaluation.evaluate(id);
    }

    @GetMapping("/{id}/llm-calls")
    public List<Views.LlmCallView> llmCalls(@PathVariable("id") String id) {
        return reviews.llmCalls(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditEvent> audit(@PathVariable("id") String id) {
        return reviews.audit(id);
    }

    @GetMapping("/{id}/threat-events")
    public List<ThreatEvent> threatEvents(@PathVariable("id") String id) {
        return reviews.threatEvents(id);
    }
}
