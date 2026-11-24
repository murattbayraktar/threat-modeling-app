package com.bank.tm.core.store;

import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.AuditEvent;
import com.bank.tm.core.domain.LlmCall;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.PipelineStep;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewDocument;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.domain.ThreatEvent;
import com.bank.tm.core.domain.ThreatRecord;

import java.util.List;
import java.util.Optional;

/**
 * Kalıcılık portu. Faz 1'de SQLite (JdbcReviewStore), testlerde bellek içi uygulama kullanılır.
 * Oracle/PostgreSQL geçişinde yalnızca bu arayüzün uygulaması ve DDL değişir.
 */
public interface ReviewStore {

    // İncelemeler
    void insertReview(Review review);

    void updateReview(Review review);

    Optional<Review> findReview(String id);

    List<Review> listReviews();

    int nextReviewSequence(int year);

    // Dokümanlar
    void insertDocument(ReviewDocument doc);

    void updateDocument(ReviewDocument doc);

    List<ReviewDocument> listDocuments(String reviewId);

    // Adımlar
    void upsertStep(PipelineStep step);

    List<PipelineStep> listSteps(String reviewId);

    // Model sürümleri
    void insertModelVersion(ModelVersion v);

    void updateModelVersion(ModelVersion v);

    List<ModelVersion> listModelVersions(String reviewId);

    default Optional<ModelVersion> latestModel(String reviewId) {
        List<ModelVersion> all = listModelVersions(reviewId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    default Optional<ModelVersion> approvedModel(String reviewId) {
        return latestModel(reviewId).filter(ModelVersion::approved);
    }

    // LLM çağrıları
    void insertLlmCall(LlmCall call);

    void updateLlmCall(LlmCall call);

    Optional<LlmCall> findLlmCall(String id);

    List<LlmCall> listLlmCalls(String reviewId);

    List<LlmCall> listLlmCallsByStatus(com.bank.tm.core.domain.LlmCallStatus status);

    // Ara çıktılar
    void putArtifact(String reviewId, ArtifactKind kind, String json);

    Optional<String> getArtifact(String reviewId, ArtifactKind kind);

    void deleteArtifactsFrom(String reviewId, Stage stage);

    // Tehditler
    void replaceThreats(String reviewId, List<ThreatRecord> threats);

    void updateThreat(ThreatRecord threat);

    void insertThreat(ThreatRecord threat);

    List<ThreatRecord> listThreats(String reviewId);

    Optional<ThreatRecord> findThreat(String id);

    void insertThreatEvent(ThreatEvent event);

    List<ThreatEvent> listThreatEvents(String reviewId);

    // Denetim izi
    void insertAudit(AuditEvent event);

    List<AuditEvent> listAudit(String reviewId);

    // Sistem parametreleri (anahtar → JSON). Değişiklik geçmişi ayrı tutulur.
    record SettingChange(String changedBy, String summary, String changedAt) {
    }

    Optional<String> getSetting(String key);

    void putSetting(String key, String json, String actor, String summary);

    List<SettingChange> listSettingHistory(String key, int limit);
}
