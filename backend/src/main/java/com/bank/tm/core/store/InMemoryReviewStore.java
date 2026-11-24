package com.bank.tm.core.store;

import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.AuditEvent;
import com.bank.tm.core.domain.LlmCall;
import com.bank.tm.core.domain.LlmCallStatus;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.PipelineStep;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewDocument;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.domain.ThreatEvent;
import com.bank.tm.core.domain.ThreatRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Testler ve hızlı denemeler için bellek içi uygulama. */
public class InMemoryReviewStore implements ReviewStore {

    private final Map<String, Review> reviews = new LinkedHashMap<>();
    private final Map<String, ReviewDocument> documents = new LinkedHashMap<>();
    private final Map<String, PipelineStep> steps = new LinkedHashMap<>();
    private final Map<String, ModelVersion> models = new LinkedHashMap<>();
    private final Map<String, LlmCall> calls = new LinkedHashMap<>();
    private final Map<String, String> artifacts = new ConcurrentHashMap<>();
    private final Map<String, ThreatRecord> threats = new LinkedHashMap<>();
    private final List<ThreatEvent> threatEvents = new ArrayList<>();
    private final List<AuditEvent> audit = new ArrayList<>();
    private int seq = 0;

    public synchronized void insertReview(Review r) { reviews.put(r.id(), r); }

    public synchronized void updateReview(Review r) { reviews.put(r.id(), r); }

    public synchronized Optional<Review> findReview(String id) { return Optional.ofNullable(reviews.get(id)); }

    public synchronized List<Review> listReviews() {
        return reviews.values().stream().sorted(Comparator.comparing(Review::createdAt).reversed()).toList();
    }

    public synchronized int nextReviewSequence(int year) { return ++seq; }

    public synchronized void insertDocument(ReviewDocument d) { documents.put(d.id(), d); }

    public synchronized void updateDocument(ReviewDocument d) { documents.put(d.id(), d); }

    public synchronized List<ReviewDocument> listDocuments(String reviewId) {
        return documents.values().stream().filter(d -> d.reviewId().equals(reviewId)).toList();
    }

    public synchronized void upsertStep(PipelineStep s) { steps.put(s.reviewId() + "/" + s.stage(), s); }

    public synchronized List<PipelineStep> listSteps(String reviewId) {
        return steps.values().stream().filter(s -> s.reviewId().equals(reviewId))
                .sorted(Comparator.comparing(PipelineStep::stage)).toList();
    }

    public synchronized void insertModelVersion(ModelVersion v) { models.put(v.id(), v); }

    public synchronized void updateModelVersion(ModelVersion v) { models.put(v.id(), v); }

    public synchronized List<ModelVersion> listModelVersions(String reviewId) {
        return models.values().stream().filter(m -> m.reviewId().equals(reviewId))
                .sorted(Comparator.comparingInt(ModelVersion::version)).toList();
    }

    public synchronized void insertLlmCall(LlmCall c) { calls.put(c.id(), c); }

    public synchronized void updateLlmCall(LlmCall c) { calls.put(c.id(), c); }

    public synchronized Optional<LlmCall> findLlmCall(String id) { return Optional.ofNullable(calls.get(id)); }

    public synchronized List<LlmCall> listLlmCalls(String reviewId) {
        return calls.values().stream().filter(c -> c.reviewId().equals(reviewId))
                .sorted(Comparator.comparing(LlmCall::createdAt)).toList();
    }

    public synchronized List<LlmCall> listLlmCallsByStatus(LlmCallStatus status) {
        return calls.values().stream().filter(c -> c.status() == status).toList();
    }

    public void putArtifact(String reviewId, ArtifactKind kind, String json) { artifacts.put(reviewId + "/" + kind, json); }

    public Optional<String> getArtifact(String reviewId, ArtifactKind kind) {
        return Optional.ofNullable(artifacts.get(reviewId + "/" + kind));
    }

    public void deleteArtifactsFrom(String reviewId, Stage stage) {
        for (ArtifactKind k : StageArtifacts.producedFrom(stage)) artifacts.remove(reviewId + "/" + k);
    }

    public synchronized void replaceThreats(String reviewId, List<ThreatRecord> list) {
        threats.values().removeIf(t -> t.reviewId().equals(reviewId));
        list.forEach(t -> threats.put(t.id(), t));
    }

    public synchronized void updateThreat(ThreatRecord t) { threats.put(t.id(), t); }

    public synchronized void insertThreat(ThreatRecord t) { threats.put(t.id(), t); }

    public synchronized List<ThreatRecord> listThreats(String reviewId) {
        return threats.values().stream().filter(t -> t.reviewId().equals(reviewId)).toList();
    }

    public synchronized Optional<ThreatRecord> findThreat(String id) { return Optional.ofNullable(threats.get(id)); }

    public synchronized void insertThreatEvent(ThreatEvent e) { threatEvents.add(e); }

    public synchronized List<ThreatEvent> listThreatEvents(String reviewId) {
        return threatEvents.stream().filter(e -> e.reviewId().equals(reviewId)).toList();
    }

    public synchronized void insertAudit(AuditEvent e) { audit.add(e); }

    public synchronized List<AuditEvent> listAudit(String reviewId) {
        return audit.stream().filter(e -> e.reviewId().equals(reviewId)).toList();
    }

    private final Map<String, String> settings = new java.util.HashMap<>();
    private final List<SettingChange> settingHistory = new ArrayList<>();

    @Override
    public synchronized Optional<String> getSetting(String key) { return Optional.ofNullable(settings.get(key)); }

    @Override
    public synchronized void putSetting(String key, String json, String actor, String summary) {
        settings.put(key, json);
        settingHistory.add(0, new SettingChange(actor, summary, java.time.Instant.now().toString()));
    }

    @Override
    public synchronized List<SettingChange> listSettingHistory(String key, int limit) {
        return settingHistory.stream().limit(limit).toList();
    }
}
