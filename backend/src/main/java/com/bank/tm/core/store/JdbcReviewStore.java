package com.bank.tm.core.store;

import com.bank.tm.core.domain.AiAssessment;
import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.AuditEvent;
import com.bank.tm.core.domain.ExpertAssessment;
import com.bank.tm.core.domain.LlmCall;
import com.bank.tm.core.domain.LlmCallStatus;
import com.bank.tm.core.domain.LlmMode;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.PipelineStep;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewDocument;
import com.bank.tm.core.domain.ReviewStatus;
import com.bank.tm.core.domain.SourceRef;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.domain.StepStatus;
import com.bank.tm.core.domain.SystemAssessment;
import com.bank.tm.core.domain.ThreatEvent;
import com.bank.tm.core.domain.ThreatRecord;
import com.bank.tm.core.llm.LlmRequest;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.util.Json;
import com.bank.tm.core.validation.ValidationIssue;
import com.fasterxml.jackson.core.type.TypeReference;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Saf JDBC ile yazılmış kalıcılık katmanı. Yalnızca standart SQL kullanır (UPSERT yerine UPDATE+INSERT);
 * veritabanına özgü kısım classpath:db/schema-{dialect}.sql dosyasıdır. SQLite → Oracle/PostgreSQL geçişinde
 * bu sınıf değişmez.
 */
public class JdbcReviewStore implements ReviewStore {

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final DataSource ds;

    public JdbcReviewStore(DataSource ds, String dialect) {
        this.ds = ds;
        migrate(dialect);
    }

    /**
     * Şema yönetimi: review tablosu yoksa temel şema, app_setting tablosu yoksa v2 (sistem parametreleri) betiği
     * çalıştırılır. Böylece mevcut veritabanları da yeni tabloları kendiliğinden alır.
     */
    private void migrate(String dialect) {
        try (Connection c = ds.getConnection()) {
            runIfMissing(c, "review", "db/schema-" + dialect + ".sql");
            runIfMissing(c, "app_setting", "db/schema-" + dialect + "-v2-settings.sql");
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Veritabanı şeması oluşturulamadı: " + e.getMessage(), e);
        }
    }

    private void runIfMissing(Connection c, String table, String path) throws SQLException, IOException {
        if (tableExists(c, table)) return;
        String script;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Şema dosyası yok: " + path);
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            String withoutComments = String.join("\n", script.lines()
                    .filter(l -> !l.strip().startsWith("--")).toList());
            for (String sql : withoutComments.split(";")) {
                if (!sql.isBlank()) st.execute(sql.strip());
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        for (String name : List.of(table, table.toUpperCase())) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    // ================================================================ İncelemeler

    @Override
    public void insertReview(Review r) {
        update("INSERT INTO review (id, code, title, business_unit, project_type, data_classification, review_owner, "
                        + "analyst, llm_mode, status, current_stage, sample_name, intake_json, expert_review_completed, "
                        + "created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.id(), r.code(), r.title(), r.businessUnit(), r.projectType(), r.dataClassification(), r.owner(),
                r.analyst(), r.llmMode().name(), r.status().name(), r.currentStage().name(), r.sampleName(),
                Json.write(r.intakeAnswers()), r.expertReviewCompleted() ? 1 : 0, r.createdBy(), ts(r.createdAt()),
                ts(r.updatedAt()));
    }

    @Override
    public void updateReview(Review r) {
        update("UPDATE review SET title=?, business_unit=?, project_type=?, data_classification=?, review_owner=?, "
                        + "analyst=?, llm_mode=?, status=?, current_stage=?, intake_json=?, expert_review_completed=?, "
                        + "updated_at=? WHERE id=?",
                r.title(), r.businessUnit(), r.projectType(), r.dataClassification(), r.owner(), r.analyst(),
                r.llmMode().name(), r.status().name(), r.currentStage().name(), Json.write(r.intakeAnswers()),
                r.expertReviewCompleted() ? 1 : 0, ts(r.updatedAt()), r.id());
    }

    @Override
    public Optional<Review> findReview(String id) {
        return one("SELECT * FROM review WHERE id=?", JdbcReviewStore::review, id);
    }

    @Override
    public List<Review> listReviews() {
        return list("SELECT * FROM review ORDER BY created_at DESC", JdbcReviewStore::review);
    }

    @Override
    public synchronized int nextReviewSequence(int year) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int next;
                try (PreparedStatement ps = c.prepareStatement("SELECT last_value FROM review_sequence WHERE seq_year=?")) {
                    ps.setInt(1, year);
                    try (ResultSet rs = ps.executeQuery()) {
                        next = rs.next() ? rs.getInt(1) + 1 : 1;
                    }
                }
                String sql = next == 1 ? "INSERT INTO review_sequence (last_value, seq_year) VALUES (?,?)"
                        : "UPDATE review_sequence SET last_value=? WHERE seq_year=?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setInt(1, next);
                    ps.setInt(2, year);
                    ps.executeUpdate();
                }
                c.commit();
                return next;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Review review(ResultSet rs) throws SQLException {
        return new Review(rs.getString("id"), rs.getString("code"), rs.getString("title"),
                rs.getString("business_unit"), rs.getString("project_type"), rs.getString("data_classification"),
                rs.getString("review_owner"), rs.getString("analyst"), LlmMode.valueOf(rs.getString("llm_mode")),
                ReviewStatus.valueOf(rs.getString("status")), Stage.valueOf(rs.getString("current_stage")),
                rs.getString("sample_name"),
                Json.read(rs.getString("intake_json"), new TypeReference<Map<String, Object>>() { }),
                rs.getInt("expert_review_completed") == 1, rs.getString("created_by"), inst(rs, "created_at"),
                inst(rs, "updated_at"));
    }

    // ================================================================ Dokümanlar

    @Override
    public void insertDocument(ReviewDocument d) {
        update("INSERT INTO review_document (id, review_id, document_key, file_name, content_type, size_bytes, sha256, "
                        + "storage_path, client_text, client_method, parsed_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                d.id(), d.reviewId(), d.documentKey(), d.fileName(), d.contentType(), d.sizeBytes(), d.sha256(),
                d.storagePath(), d.clientText(), d.clientExtractionMethod(), Json.write(d.parsed()), ts(d.createdAt()));
    }

    @Override
    public void updateDocument(ReviewDocument d) {
        update("UPDATE review_document SET parsed_json=?, client_text=?, client_method=? WHERE id=?",
                Json.write(d.parsed()), d.clientText(), d.clientExtractionMethod(), d.id());
    }

    @Override
    public List<ReviewDocument> listDocuments(String reviewId) {
        return list("SELECT * FROM review_document WHERE review_id=? ORDER BY document_key", rs -> new ReviewDocument(
                rs.getString("id"), rs.getString("review_id"), rs.getString("document_key"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("storage_path"), rs.getString("client_text"), rs.getString("client_method"),
                Json.read(rs.getString("parsed_json"), ParsedDocument.class), inst(rs, "created_at")), reviewId);
    }

    // ================================================================ Adımlar

    @Override
    public void upsertStep(PipelineStep s) {
        Object[] values = {s.status().name(), truncate(s.message(), 1000), Json.write(s.metrics()), ts(s.startedAt()), ts(s.finishedAt()),
                s.reviewId(), s.stage().name()};
        int n = update("UPDATE pipeline_step SET status=?, message=?, metrics_json=?, started_at=?, finished_at=? "
                + "WHERE review_id=? AND stage=?", values);
        if (n == 0) {
            update("INSERT INTO pipeline_step (status, message, metrics_json, started_at, finished_at, review_id, stage, "
                    + "stage_order) VALUES (?,?,?,?,?,?,?,?)", s.status().name(), truncate(s.message(), 1000),
                    Json.write(s.metrics()), ts(s.startedAt()), ts(s.finishedAt()), s.reviewId(), s.stage().name(),
                    s.stage().ordinal());
        }
    }

    @Override
    public List<PipelineStep> listSteps(String reviewId) {
        return list("SELECT * FROM pipeline_step WHERE review_id=? ORDER BY stage_order", rs -> new PipelineStep(
                rs.getString("review_id"), Stage.valueOf(rs.getString("stage")),
                StepStatus.valueOf(rs.getString("status")), rs.getString("message"),
                Json.read(rs.getString("metrics_json"), new TypeReference<Map<String, Object>>() { }),
                inst(rs, "started_at"), inst(rs, "finished_at")), reviewId);
    }

    // ================================================================ Model sürümleri

    @Override
    public void insertModelVersion(ModelVersion v) {
        update("INSERT INTO model_version (id, review_id, version_no, source_kind, model_json, issues_json, created_by, "
                        + "created_at, approved_by, approved_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                v.id(), v.reviewId(), v.version(), v.source(), Json.write(v.model()), Json.write(v.issues()),
                v.createdBy(), ts(v.createdAt()), v.approvedBy(), ts(v.approvedAt()));
    }

    @Override
    public void updateModelVersion(ModelVersion v) {
        update("UPDATE model_version SET model_json=?, issues_json=?, approved_by=?, approved_at=? WHERE id=?",
                Json.write(v.model()), Json.write(v.issues()), v.approvedBy(), ts(v.approvedAt()), v.id());
    }

    @Override
    public List<ModelVersion> listModelVersions(String reviewId) {
        return list("SELECT * FROM model_version WHERE review_id=? ORDER BY version_no", rs -> new ModelVersion(
                rs.getString("id"), rs.getString("review_id"), rs.getInt("version_no"), rs.getString("source_kind"),
                Json.read(rs.getString("model_json"), SystemModel.class),
                Json.read(rs.getString("issues_json"), new TypeReference<List<ValidationIssue>>() { }),
                rs.getString("created_by"), inst(rs, "created_at"), rs.getString("approved_by"),
                inst(rs, "approved_at")), reviewId);
    }

    // ================================================================ LLM çağrıları

    @Override
    public void insertLlmCall(LlmCall c) {
        update("INSERT INTO llm_call (id, review_id, stage, call_key, attempt, executor, model_name, status, request_json, "
                        + "raw_output, errors_json, duration_ms, created_at, completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.id(), c.reviewId(), c.stage().name(), c.callKey(), c.attempt(), c.executor(), c.model(),
                c.status().name(), Json.write(c.request()), c.rawOutput(), Json.write(c.errors()), c.durationMs(),
                ts(c.createdAt()), ts(c.completedAt()));
    }

    @Override
    public void updateLlmCall(LlmCall c) {
        update("UPDATE llm_call SET model_name=?, status=?, raw_output=?, errors_json=?, duration_ms=?, completed_at=? "
                        + "WHERE id=?", c.model(), c.status().name(), c.rawOutput(), Json.write(c.errors()),
                c.durationMs(), ts(c.completedAt()), c.id());
    }

    @Override
    public Optional<LlmCall> findLlmCall(String id) {
        return one("SELECT * FROM llm_call WHERE id=?", JdbcReviewStore::llmCall, id);
    }

    @Override
    public List<LlmCall> listLlmCalls(String reviewId) {
        return list("SELECT * FROM llm_call WHERE review_id=? ORDER BY created_at, attempt", JdbcReviewStore::llmCall, reviewId);
    }

    @Override
    public List<LlmCall> listLlmCallsByStatus(LlmCallStatus status) {
        return list("SELECT * FROM llm_call WHERE status=? ORDER BY created_at", JdbcReviewStore::llmCall, status.name());
    }

    private static LlmCall llmCall(ResultSet rs) throws SQLException {
        long ms = rs.getLong("duration_ms");
        Long duration = rs.wasNull() ? null : ms;
        return new LlmCall(rs.getString("id"), rs.getString("review_id"), Stage.valueOf(rs.getString("stage")),
                rs.getString("call_key"), rs.getInt("attempt"), rs.getString("executor"), rs.getString("model_name"),
                LlmCallStatus.valueOf(rs.getString("status")), Json.read(rs.getString("request_json"), LlmRequest.class),
                rs.getString("raw_output"), Json.read(rs.getString("errors_json"), new TypeReference<List<String>>() { }),
                duration, inst(rs, "created_at"), inst(rs, "completed_at"));
    }

    // ================================================================ Ara çıktılar

    @Override
    public void putArtifact(String reviewId, ArtifactKind kind, String json) {
        String now = ts(Instant.now());
        int n = update("UPDATE review_artifact SET json_value=?, updated_at=? WHERE review_id=? AND kind=?",
                json, now, reviewId, kind.name());
        if (n == 0) {
            update("INSERT INTO review_artifact (json_value, updated_at, review_id, kind) VALUES (?,?,?,?)",
                    json, now, reviewId, kind.name());
        }
    }

    @Override
    public Optional<String> getArtifact(String reviewId, ArtifactKind kind) {
        return one("SELECT json_value FROM review_artifact WHERE review_id=? AND kind=?", rs -> rs.getString(1),
                reviewId, kind.name());
    }

    @Override
    public void deleteArtifactsFrom(String reviewId, Stage stage) {
        for (ArtifactKind k : StageArtifacts.producedFrom(stage)) {
            update("DELETE FROM review_artifact WHERE review_id=? AND kind=?", reviewId, k.name());
        }
    }

    // ================================================================ Tehditler

    @Override
    public void replaceThreats(String reviewId, List<ThreatRecord> threats) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM threat WHERE review_id=?")) {
                    ps.setString(1, reviewId);
                    ps.executeUpdate();
                }
                for (ThreatRecord t : threats) insertThreat(c, t);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void insertThreat(ThreatRecord t) {
        try (Connection c = ds.getConnection()) {
            insertThreat(c, t);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertThreat(Connection c, ThreatRecord t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO threat (id, review_id, code, model_version, candidate_id, "
                + "rule_id, stride, target_id, target_name, ai_additional, decision, ai_json, system_json, expert_json, "
                + "sources_json, issues_json, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bind(ps, t.id(), t.reviewId(), t.code(), t.modelVersion(), t.candidateId(), t.ruleId(), t.stride().name(),
                    t.targetId(), t.targetName(), t.aiAdditional() ? 1 : 0, t.decision().name(), Json.write(t.ai()),
                    Json.write(t.system()), Json.write(t.expert()), Json.write(t.sources()), Json.write(t.issues()),
                    ts(t.createdAt()), ts(t.updatedAt()));
            ps.executeUpdate();
        }
    }

    @Override
    public void updateThreat(ThreatRecord t) {
        update("UPDATE threat SET decision=?, system_json=?, expert_json=?, updated_at=? WHERE id=?",
                t.decision().name(), Json.write(t.system()), Json.write(t.expert()), ts(t.updatedAt()), t.id());
    }

    @Override
    public List<ThreatRecord> listThreats(String reviewId) {
        return list("SELECT * FROM threat WHERE review_id=? ORDER BY created_at, code", JdbcReviewStore::threat, reviewId);
    }

    @Override
    public Optional<ThreatRecord> findThreat(String id) {
        return one("SELECT * FROM threat WHERE id=?", JdbcReviewStore::threat, id);
    }

    private static ThreatRecord threat(ResultSet rs) throws SQLException {
        return new ThreatRecord(rs.getString("id"), rs.getString("review_id"), rs.getString("code"),
                rs.getInt("model_version"), rs.getString("candidate_id"), rs.getString("rule_id"),
                Stride.valueOf(rs.getString("stride")), rs.getString("target_id"), rs.getString("target_name"),
                rs.getInt("ai_additional") == 1, Json.read(rs.getString("ai_json"), AiAssessment.class),
                Json.read(rs.getString("system_json"), SystemAssessment.class),
                Json.read(rs.getString("expert_json"), ExpertAssessment.class),
                Json.read(rs.getString("sources_json"), new TypeReference<List<SourceRef>>() { }),
                Json.read(rs.getString("issues_json"), new TypeReference<List<ValidationIssue>>() { }),
                inst(rs, "created_at"), inst(rs, "updated_at"));
    }

    @Override
    public void insertThreatEvent(ThreatEvent e) {
        update("INSERT INTO threat_event (id, threat_id, review_id, actor, action, before_json, after_json, note, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", e.id(), e.threatId(), e.reviewId(), e.actor(), e.action(),
                e.beforeJson(), e.afterJson(), truncate(e.note(), 2000), ts(e.createdAt()));
    }

    @Override
    public List<ThreatEvent> listThreatEvents(String reviewId) {
        return list("SELECT * FROM threat_event WHERE review_id=? ORDER BY created_at", rs -> new ThreatEvent(
                rs.getString("id"), rs.getString("threat_id"), rs.getString("review_id"), rs.getString("actor"),
                rs.getString("action"), rs.getString("before_json"), rs.getString("after_json"), rs.getString("note"),
                inst(rs, "created_at")), reviewId);
    }

    // ================================================================ Denetim izi

    @Override
    public void insertAudit(AuditEvent e) {
        update("INSERT INTO audit_event (id, review_id, actor, event_type, message, created_at) VALUES (?,?,?,?,?,?)",
                e.id(), e.reviewId(), e.actor(), e.type(), truncate(e.message(), 2000), ts(e.createdAt()));
    }

    @Override
    public List<AuditEvent> listAudit(String reviewId) {
        return list("SELECT * FROM audit_event WHERE review_id=? ORDER BY created_at", rs -> new AuditEvent(
                rs.getString("id"), rs.getString("review_id"), rs.getString("actor"), rs.getString("event_type"),
                rs.getString("message"), inst(rs, "created_at")), reviewId);
    }

    // ================================================================ JDBC yardımcıları

    // ================================================================ Sistem parametreleri

    @Override
    public Optional<String> getSetting(String key) {
        return one("SELECT json_value FROM app_setting WHERE setting_key=?", rs -> rs.getString(1), key);
    }

    @Override
    public void putSetting(String key, String json, String actor, String summary) {
        String now = java.time.Instant.now().toString();
        int n = update("UPDATE app_setting SET json_value=?, updated_by=?, updated_at=? WHERE setting_key=?", json, actor, now, key);
        if (n == 0) update("INSERT INTO app_setting (setting_key, json_value, updated_by, updated_at) VALUES (?,?,?,?)", key, json, actor, now);
        update("INSERT INTO app_setting_history (id, setting_key, summary, changed_by, changed_at) VALUES (?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(), key, summary == null ? null : summary.substring(0, Math.min(summary.length(), 3900)), actor, now);
    }

    @Override
    public List<SettingChange> listSettingHistory(String key, int limit) {
        List<SettingChange> all = list("SELECT * FROM app_setting_history WHERE setting_key=? ORDER BY changed_at DESC",
                rs -> new SettingChange(rs.getString("changed_by"), rs.getString("summary"), rs.getString("changed_at")), key);
        return all.subList(0, Math.min(limit, all.size()));
    }

    private int update(String sql, Object... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL hatası: " + e.getMessage(), e);
        }
    }

    private <T> List<T> list(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL hatası: " + e.getMessage(), e);
        }
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) {
        List<T> l = list(sql, mapper, params);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setNull(i + 1, Types.VARCHAR);
            else if (p instanceof Integer v) ps.setInt(i + 1, v);
            else if (p instanceof Long v) ps.setLong(i + 1, v);
            else ps.setString(i + 1, p.toString());
        }
    }

    private static String ts(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        String v = rs.getString(col);
        return v == null ? null : Instant.parse(v);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
