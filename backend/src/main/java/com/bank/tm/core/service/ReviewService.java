package com.bank.tm.core.service;

import com.bank.tm.core.domain.AiAssessment;
import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.AuditEvent;
import com.bank.tm.core.domain.Decision;
import com.bank.tm.core.domain.ExpertAssessment;
import com.bank.tm.core.domain.LlmCall;
import com.bank.tm.core.domain.LlmCallStatus;
import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.domain.Mitigation;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.PipelineStep;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewDocument;
import com.bank.tm.core.domain.ReviewStatus;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.domain.StepStatus;
import com.bank.tm.core.domain.SystemAssessment;
import com.bank.tm.core.domain.ThreatEvent;
import com.bank.tm.core.domain.ThreatRecord;
import com.bank.tm.core.llm.LlmGateway;
import com.bank.tm.core.model.Confidence;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.model.ThreatCandidate;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.pipeline.DocumentStorage;
import com.bank.tm.core.pipeline.PipelineOrchestrator;
import com.bank.tm.core.pipeline.Stages;
import com.bank.tm.core.rag.KnowledgeBase;
import com.bank.tm.core.risk.RiskEngine;
import com.bank.tm.core.rules.Facts;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.util.Clock;
import com.bank.tm.core.util.Ids;
import com.bank.tm.core.util.Json;
import com.bank.tm.core.validation.ModelValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Uygulama servisi: REST katmanının çağırdığı tüm kullanım senaryoları. */
public class ReviewService {

    public record UploadedFile(String fileName, String contentType, byte[] content, String clientText,
                               String clientMethod) {
    }

    public record CreateReview(String title, String businessUnit, String projectType, String dataClassification,
                               String owner, String analyst, LlmMode llmMode, Map<String, Object> answers) {
    }

    /** null alanlar değiştirilmez. resetRisk=true uzmanın olasılık/etki değişikliğini geri alır. */
    public record ThreatUpdate(Decision decision, String title, String description, Integer likelihood, Integer impact,
                               Boolean resetRisk, String rationale, List<Mitigation> mitigations, String note) {
    }

    public record NewThreat(Stride stride, String targetId, String title, String description, Integer likelihood,
                            Integer impact, List<Mitigation> mitigations, String note) {
    }

    private final ReviewStore store;
    private final DocumentStorage storage;
    private final PipelineOrchestrator orchestrator;
    private final LlmGateway gateway;
    private final ModelValidator modelValidator;
    private final RiskEngine risk;
    private final KnowledgeBase knowledgeBase;
    private final ReportBuilder reports;
    private final SampleLibrary samples;
    private final java.util.function.Supplier<TmSettings> settingsSource;

    public ReviewService(ReviewStore store, DocumentStorage storage, PipelineOrchestrator orchestrator,
                         LlmGateway gateway, ModelValidator modelValidator, RiskEngine risk, KnowledgeBase knowledgeBase,
                         ReportBuilder reports, SampleLibrary samples, java.util.function.Supplier<TmSettings> settingsSource) {
        this.store = store;
        this.storage = storage;
        this.orchestrator = orchestrator;
        this.gateway = gateway;
        this.modelValidator = modelValidator;
        this.risk = risk;
        this.knowledgeBase = knowledgeBase;
        this.reports = reports;
        this.samples = samples;
        this.settingsSource = settingsSource;
    }

    // ------------------------------------------------------------------ oluşturma

    public Review create(CreateReview cmd, List<UploadedFile> files, String user, String sampleName) {
        if (cmd.title() == null || cmd.title().isBlank()) throw new IllegalArgumentException("Proje adı zorunlu");
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("En az bir doküman yüklenmeli");
        TmSettings settings = settingsSource.get();
        LlmMode mode = cmd.llmMode() == null ? settings.defaultLlmMode() : cmd.llmMode();
        if (mode == LlmMode.BROWSER && !settings.browserLlmEnabled()) {
            throw new IllegalArgumentException("Tarayıcı LLM modu bu kurulumda kapalı");
        }
        int year = Year.now().getValue();
        String code = String.format("TM-%d-%03d", year, store.nextReviewSequence(year));
        var now = Clock.now();
        Review review = new Review(Ids.uuid(), code, cmd.title().strip(), cmd.businessUnit(), cmd.projectType(),
                cmd.dataClassification(), cmd.owner(), cmd.analyst(), mode, ReviewStatus.IN_PROGRESS, Stage.UPLOAD,
                sampleName, cmd.answers(), false, user, now, now);
        store.insertReview(review);
        int i = 1;
        for (UploadedFile f : files) {
            String key = "D" + i++;
            String path = storage.save(review.id(), key, f.fileName(), f.content());
            store.insertDocument(new ReviewDocument(Ids.uuid(), review.id(), key, f.fileName(), f.contentType(),
                    f.content().length, sha256(f.content()), path, f.clientText(), f.clientMethod(), null, now));
        }
        orchestrator.initialize(review.id());
        audit(review.id(), user, "REVIEW_CREATED", files.size() + " doküman ile inceleme oluşturuldu (LLM: " + mode + ")");
        orchestrator.trigger(review.id());
        return review;
    }

    public Review createFromSample(String sampleName, String user, LlmMode mode, Map<String, String> clientTexts) {
        SampleLibrary.Sample sample = samples.get(sampleName);
        JsonNode in = sample.intake();
        Map<String, Object> answers = in.has("answers")
                ? Json.MAPPER.convertValue(in.get("answers"), new TypeReference<Map<String, Object>>() { }) : Map.of();
        List<UploadedFile> files = new ArrayList<>();
        for (SampleLibrary.SampleFile f : sample.files()) {
            String clientText = clientTexts == null ? null : clientTexts.get(f.fileName());
            files.add(new UploadedFile(f.fileName(), f.contentType(), samples.read(sampleName, f.fileName()), clientText,
                    clientText == null ? null : "browser-ocr"));
        }
        return create(new CreateReview(in.path("title").asText(sampleName), in.path("businessUnit").asText(null),
                in.path("projectType").asText(null), in.path("dataClassification").asText(null),
                in.path("owner").asText(null), user, mode, answers), files, user, sampleName);
    }

    // ------------------------------------------------------------------ sorgular

    public List<Views.ReviewSummary> list() {
        return store.listReviews().stream().map(r -> {
            List<ThreatRecord> threats = store.listThreats(r.id());
            return new Views.ReviewSummary(r, levels(threats), threats.size(),
                    threats.stream().filter(t -> t.decision() == Decision.PENDING).count());
        }).toList();
    }

    public Views.ReviewDetail detail(String id) {
        Review r = review(id);
        List<ThreatRecord> threats = store.listThreats(id);
        Optional<ModelVersion> latest = store.latestModel(id);
        return new Views.ReviewDetail(r, store.listSteps(id).stream().map(Views.StepView::of).toList(),
                store.listDocuments(id).stream().map(Views.DocumentView::of).toList(),
                latest.map(ModelVersion::version).orElse(null), latest.map(ModelVersion::approved).orElse(false),
                gateway.pendingFor(id).map(LlmCall::id).orElse(null), levels(threats), threats.size(),
                threats.stream().filter(t -> t.decision() == Decision.PENDING).count(), orchestrator.isRunning(id));
    }

    public List<ParsedDocument> parsedDocuments(String id) {
        review(id);
        return store.listDocuments(id).stream().map(ReviewDocument::parsed).filter(p -> p != null).toList();
    }

    public Views.ModelView model(String id) {
        review(id);
        List<ModelVersion> versions = store.listModelVersions(id);
        ModelVersion current = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        return new Views.ModelView(current, versions.stream().map(Views.ModelVersionMeta::of).toList(),
                isWaiting(id, Stage.MODEL_REVIEW));
    }

    public List<ThreatCandidate> candidates(String id) {
        review(id);
        return store.getArtifact(id, ArtifactKind.THREAT_CANDIDATES)
                .map(j -> Json.read(j, new TypeReference<List<ThreatCandidate>>() { })).orElse(List.of());
    }

    /** Aday başına getirilen bilgi tabanı kayıtları (skorlarıyla). */
    public Map<String, Object> retrieval(String id) {
        review(id);
        Optional<String> raw = store.getArtifact(id, ArtifactKind.KNOWLEDGE_RETRIEVAL);
        if (raw.isEmpty()) return Map.of();
        Stages.RetrievalArtifact a = Json.read(raw.get(), Stages.RetrievalArtifact.class);
        Map<String, Object> chunks = new LinkedHashMap<>();
        a.perCandidate().values().forEach(l -> l.forEach(h -> knowledgeBase.get(h.id()).ifPresent(k -> chunks.put(k.id(), k))));
        return Map.of("mode", a.mode(), "perCandidate", a.perCandidate(), "chunks", chunks);
    }

    public List<Views.ThreatView> threats(String id) {
        review(id);
        return store.listThreats(id).stream().map(Views.ThreatView::of).toList();
    }

    public List<Views.LlmCallView> llmCalls(String id) {
        review(id);
        return store.listLlmCalls(id).stream().map(Views.LlmCallView::of).toList();
    }

    public List<AuditEvent> audit(String id) {
        review(id);
        return store.listAudit(id);
    }

    public List<ThreatEvent> threatEvents(String id) {
        review(id);
        return store.listThreatEvents(id);
    }

    public Map<String, Object> artifact(String id, ArtifactKind kind) {
        review(id);
        return store.getArtifact(id, kind).map(j -> Map.<String, Object>of("kind", kind.name(), "data", Json.tree(j)))
                .orElseThrow(() -> new NotFoundException("Ara çıktı yok: " + kind));
    }

    public ReportBuilder.Report report(String id) {
        return reports.build(id);
    }

    // ------------------------------------------------------------------ model onayı

    public ModelVersion updateModel(String id, SystemModel edited, String user) {
        requireWaiting(id, Stage.MODEL_REVIEW, "Model yalnızca onay adımında düzenlenebilir");
        List<ParsedDocument> docs = parsedDocuments(id);
        ModelValidator.Result res = modelValidator.validate(edited, docs, true);
        int version = store.listModelVersions(id).size() + 1;
        ModelVersion v = new ModelVersion(Ids.uuid(), id, version, "ANALYST", res.model(), res.issues(), user,
                Clock.now(), null, null);
        store.insertModelVersion(v);
        audit(id, user, "MODEL_EDITED", "Model v" + version + " analist tarafından kaydedildi");
        return v;
    }

    public ModelVersion approveModel(String id, String user) {
        requireWaiting(id, Stage.MODEL_REVIEW, "Onay bekleyen bir model yok");
        ModelVersion v = store.latestModel(id).orElseThrow(() -> new ConflictException("Model yok"));
        if (v.model().components().isEmpty()) throw new ConflictException("Bileşeni olmayan model onaylanamaz");
        ModelVersion approved = v.approve(user, Clock.now());
        store.updateModelVersion(approved);
        audit(id, user, "MODEL_APPROVED", "Model v" + v.version() + " onaylandı");
        orchestrator.trigger(id);
        return approved;
    }

    public void rejectModel(String id, String note, String user) {
        requireWaiting(id, Stage.MODEL_REVIEW, "Yeniden çıkarım yalnızca onay adımında istenebilir");
        Review r = review(id);
        Map<String, Object> answers = new HashMap<>(r.intakeAnswers());
        if (note != null && !note.isBlank()) answers.put("analystNote", note.strip());
        else answers.remove("analystNote");
        store.updateReview(new Review(r.id(), r.code(), r.title(), r.businessUnit(), r.projectType(),
                r.dataClassification(), r.owner(), r.analyst(), r.llmMode(), r.status(), r.currentStage(),
                r.sampleName(), answers, r.expertReviewCompleted(), r.createdBy(), r.createdAt(), Clock.now()));
        orchestrator.resetFrom(id, Stage.MODEL_EXTRACTION);
        audit(id, user, "MODEL_REJECTED", "Model yeniden çıkarılıyor" + (note == null || note.isBlank() ? "" : ": " + note));
        orchestrator.trigger(id);
    }

    /** Onaylı modele geri dönüp düzenleme: tehditler silinir, yeni (onaysız) model sürümü açılır. */
    public ModelVersion backToModel(String id, String user) {
        ModelVersion approved = store.approvedModel(id).orElseThrow(() -> new ConflictException("Onaylı model yok"));
        if (store.findReview(id).map(Review::status).orElse(null) == ReviewStatus.COMPLETED) {
            throw new ConflictException("Tamamlanmış inceleme yeniden açılmadan modele dönülemez");
        }
        orchestrator.resetFrom(id, Stage.MODEL_REVIEW);
        ModelVersion v = new ModelVersion(Ids.uuid(), id, approved.version() + 1, "ANALYST", approved.model(),
                approved.issues(), user, Clock.now(), null, null);
        store.insertModelVersion(v);
        audit(id, user, "BACK_TO_MODEL", "Modele dönüldü; mevcut tehditler ve kararlar silindi");
        orchestrator.trigger(id);
        return v;
    }

    // ------------------------------------------------------------------ uzman incelemesi

    public Views.ThreatView updateThreat(String id, String threatId, ThreatUpdate u, String user) {
        requireExpertReview(id);
        ThreatRecord t = threat(id, threatId);
        ExpertAssessment e = t.expert();
        Integer l = e.likelihood();
        Integer i = e.impact();
        if (Boolean.TRUE.equals(u.resetRisk())) {
            l = null;
            i = null;
        }
        if (u.likelihood() != null) l = check(u.likelihood());
        if (u.impact() != null) i = check(u.impact());
        Integer score = null;
        String level = null;
        if (l != null || i != null) {
            int fl = l != null ? l : t.system().likelihood();
            int fi = i != null ? i : t.system().impact();
            if (l == null) l = fl;
            if (i == null) i = fi;
            score = risk.score(fl, fi);
            level = risk.level(score);
        }
        List<Mitigation> mitigations = u.mitigations() == null ? e.mitigations()
                : u.mitigations().stream().map(m -> new Mitigation(m.title(), m.description(), m.sourceIds(),
                m.origin() == null ? "EXPERT" : m.origin())).toList();
        ExpertAssessment next = new ExpertAssessment(
                u.decision() != null ? u.decision() : e.decision(),
                u.title() != null ? blankToNull(u.title()) : e.title(),
                u.description() != null ? blankToNull(u.description()) : e.description(),
                l, i, score, level,
                u.rationale() != null ? u.rationale() : e.rationale(),
                mitigations,
                u.note() != null ? u.note() : e.note(),
                user, Clock.now());
        if ((l != null || i != null) && (next.rationale() == null || next.rationale().isBlank())) {
            throw new IllegalArgumentException("Risk değerini değiştirirken gerekçe yazılmalı");
        }
        ThreatRecord updated = t.withExpert(next, Clock.now());
        store.updateThreat(updated);
        store.insertThreatEvent(new ThreatEvent(Ids.uuid(), t.id(), id, user,
                u.decision() != null ? "DECISION_" + u.decision() : "EDIT", Json.write(t.expert()), Json.write(next),
                u.note(), Clock.now()));
        return Views.ThreatView.of(updated);
    }

    public Views.ThreatView addThreat(String id, NewThreat n, String user) {
        requireExpertReview(id);
        ModelVersion mv = store.approvedModel(id).orElseThrow();
        if (n.stride() == null || n.targetId() == null || !mv.model().hasElement(n.targetId())) {
            throw new IllegalArgumentException("Geçerli bir STRIDE kategorisi ve modeldeki bir hedef seçilmeli");
        }
        if (n.title() == null || n.title().isBlank()) throw new IllegalArgumentException("Başlık zorunlu");
        Review r = review(id);
        SystemAssessment sys = risk.assess(n.stride(), null, null,
                Facts.forThreat(mv.model(), n.targetId(), n.stride(), "MANUAL", false, r));
        List<Mitigation> mitigations = n.mitigations() == null ? List.of()
                : n.mitigations().stream().map(m -> new Mitigation(m.title(), m.description(), m.sourceIds(), "EXPERT")).toList();
        AiAssessment ai = new AiAssessment(n.title(), n.description(), null, null, null, null, List.of(),
                Confidence.HIGH, List.of(), true, null, null, false);
        Integer l = n.likelihood() == null ? null : check(n.likelihood());
        Integer i = n.impact() == null ? null : check(n.impact());
        Integer score = null;
        String level = null;
        if (l != null || i != null) {
            if (l == null) l = sys.likelihood();
            if (i == null) i = sys.impact();
            score = risk.score(l, i);
            level = risk.level(score);
        }
        ExpertAssessment expert = new ExpertAssessment(Decision.ACCEPTED, null, null, l, i, score, level,
                score == null ? null : "Uzman tarafından eklendi", mitigations, n.note(), user, Clock.now());
        int next = store.listThreats(id).size() + 1;
        ThreatRecord t = new ThreatRecord(Ids.uuid(), id, "T" + next + "-U", mv.version(), null, "MANUAL", n.stride(),
                n.targetId(), mv.model().elementName(n.targetId()), false, ai, sys, expert, List.of(), List.of(),
                Clock.now(), Clock.now());
        store.insertThreat(t);
        store.insertThreatEvent(new ThreatEvent(Ids.uuid(), t.id(), id, user, "MANUAL_ADD", null, Json.write(expert),
                n.note(), Clock.now()));
        return Views.ThreatView.of(t);
    }

    public void completeExpertReview(String id, String user) {
        requireExpertReview(id);
        long pending = store.listThreats(id).stream().filter(t -> t.decision() == Decision.PENDING).count();
        if (pending > 0) throw new ConflictException(pending + " tehdit için karar verilmedi");
        Review r = review(id);
        store.updateReview(r.withExpertReviewCompleted(true, Clock.now()));
        audit(id, user, "EXPERT_REVIEW_COMPLETED", "Uzman incelemesi tamamlandı");
        orchestrator.trigger(id);
    }

    public void reopenExpertReview(String id, String user) {
        if (review(id).status() != ReviewStatus.COMPLETED) throw new ConflictException("İnceleme tamamlanmamış");
        orchestrator.resetFrom(id, Stage.EXPERT_REVIEW);
        audit(id, user, "EXPERT_REVIEW_REOPENED", "Uzman incelemesi yeniden açıldı");
        orchestrator.trigger(id);
    }

    // ------------------------------------------------------------------ akış kontrolü

    public void retry(String id, String user) {
        PipelineStep failed = store.listSteps(id).stream().filter(s -> s.status() == StepStatus.FAILED).findFirst()
                .orElseThrow(() -> new ConflictException("Başarısız adım yok"));
        store.upsertStep(PipelineStep.pending(id, failed.stage()));
        if (failed.stage().actor() == Stage.Actor.LLM) gateway.supersedeFrom(id, failed.stage());
        audit(id, user, "RETRY", failed.stage().label() + " yeniden deneniyor");
        orchestrator.trigger(id);
    }

    /** LLM modunu değiştirir (ör. tarayıcıda model indirilemiyorsa sunucuya geçmek için). */
    public Review setLlmMode(String id, LlmMode mode, String user) {
        Review r = review(id);
        if (mode == LlmMode.BROWSER && !settingsSource.get().browserLlmEnabled()) throw new IllegalArgumentException("Tarayıcı LLM modu kapalı");
        for (LlmCall c : store.listLlmCalls(id)) {
            if (c.status() == LlmCallStatus.PENDING) store.updateLlmCall(c.withStatus(LlmCallStatus.SUPERSEDED));
        }
        Review updated = r.withLlmMode(mode, Clock.now());
        store.updateReview(updated);
        store.listSteps(id).stream().filter(s -> s.status() == StepStatus.WAITING_LLM)
                .forEach(s -> store.upsertStep(PipelineStep.pending(id, s.stage())));
        audit(id, user, "LLM_MODE", "LLM modu " + mode + " olarak değiştirildi");
        orchestrator.trigger(id);
        return updated;
    }

    public List<Views.LlmTaskView> pendingLlmTasks(String reviewId) {
        return store.listLlmCallsByStatus(LlmCallStatus.PENDING).stream()
                .filter(c -> reviewId == null || c.reviewId().equals(reviewId))
                .map(Views.LlmTaskView::of).toList();
    }

    public void submitLlmResult(String callId, String rawOutput, String model, Long durationMs, String user) {
        LlmCall c = gateway.submitBrowserResult(callId, rawOutput, model, durationMs);
        audit(c.reviewId(), user, "BROWSER_LLM_RESULT", c.stage().label() + " (" + c.callKey() + ") tarayıcıdan gönderildi"
                + (model == null ? "" : " – " + model));
        orchestrator.trigger(c.reviewId());
    }

    // ------------------------------------------------------------------ yardımcılar

    private Review review(String id) {
        return store.findReview(id).orElseThrow(() -> new NotFoundException("İnceleme bulunamadı: " + id));
    }

    private ThreatRecord threat(String reviewId, String threatId) {
        ThreatRecord t = store.findThreat(threatId).orElseThrow(() -> new NotFoundException("Tehdit bulunamadı"));
        if (!t.reviewId().equals(reviewId)) throw new NotFoundException("Tehdit bu incelemeye ait değil");
        return t;
    }

    private boolean isWaiting(String id, Stage stage) {
        return store.listSteps(id).stream().anyMatch(s -> s.stage() == stage && s.status() == StepStatus.WAITING_USER);
    }

    private void requireWaiting(String id, Stage stage, String message) {
        review(id);
        if (!isWaiting(id, stage)) throw new ConflictException(message);
    }

    private void requireExpertReview(String id) {
        requireWaiting(id, Stage.EXPERT_REVIEW, "Tehditler yalnızca uzman inceleme adımında düzenlenebilir");
    }

    private void audit(String reviewId, String user, String type, String message) {
        store.insertAudit(new AuditEvent(Ids.uuid(), reviewId, user, type, message, Clock.now()));
    }

    private static Map<String, Long> levels(List<ThreatRecord> threats) {
        return threats.stream().filter(t -> t.finalLevel() != null && t.decision() != Decision.REJECTED
                        && t.decision() != Decision.FALSE_POSITIVE)
                .collect(Collectors.groupingBy(ThreatRecord::finalLevel, LinkedHashMap::new, Collectors.counting()));
    }

    private static int check(int v) {
        if (v < 1 || v > 5) throw new IllegalArgumentException("Olasılık ve etki 1-5 arasında olmalı");
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static <T> Map<String, T> index(List<T> list, Function<T, String> key) {
        return list.stream().collect(Collectors.toMap(key, x -> x, (a, b) -> a, LinkedHashMap::new));
    }
}
