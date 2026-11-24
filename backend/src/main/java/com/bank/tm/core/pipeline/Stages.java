package com.bank.tm.core.pipeline;

import com.bank.tm.core.domain.AiAssessment;
import com.bank.tm.core.domain.ArtifactKind;
import com.bank.tm.core.domain.ExpertAssessment;
import com.bank.tm.core.domain.Mitigation;
import com.bank.tm.core.domain.ModelVersion;
import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ReviewDocument;
import com.bank.tm.core.domain.SourceRef;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.domain.SystemAssessment;
import com.bank.tm.core.domain.ThreatRecord;
import com.bank.tm.core.llm.LlmGateway;
import com.bank.tm.core.llm.LlmRequest;
import com.bank.tm.core.llm.LlmTask;
import com.bank.tm.core.llm.PromptLibrary;
import com.bank.tm.core.llm.SchemaRegistry;
import com.bank.tm.core.model.ModelExtractionOutput;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.model.ThreatCandidate;
import com.bank.tm.core.model.ThreatGenerationOutput;
import com.bank.tm.core.model.ThreatGenerationOutput.LlmThreat;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.rag.KnowledgeChunk;
import com.bank.tm.core.rag.RetrievedChunk;
import com.bank.tm.core.rag.Retriever;
import com.bank.tm.core.rules.Facts;
import com.bank.tm.core.util.Clock;
import com.bank.tm.core.util.Ids;
import com.bank.tm.core.util.Json;
import com.bank.tm.core.util.Text;
import com.bank.tm.core.validation.ModelValidator;
import com.bank.tm.core.validation.ThreatValidator;
import com.bank.tm.core.validation.ValidationIssue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Faz 1 akışının adım uygulamaları. Her adım ara çıktısını ReviewStore'a yazar, sonraki adım oradan okur. */
public final class Stages {

    private Stages() {
    }

    public static List<StageHandler> all(PipelineServices s) {
        return List.of(new Upload(s), new Parsing(s), new ModelExtraction(s), new ModelValidation(s),
                new ModelReview(s), new Candidates(s), new Retrieval(s), new ThreatGeneration(s),
                new ThreatValidation(s), new RiskAssessment(s), new ExpertReview(s), new Report(s));
    }

    // ---------------------------------------------------------------- 1. Yükleme
    static final class Upload implements StageHandler {
        private final PipelineServices s;

        Upload(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.UPLOAD; }

        public StageResult run(Review r) {
            int n = s.store().listDocuments(r.id()).size();
            if (n == 0) throw new IllegalStateException("İncelemeye doküman yüklenmemiş.");
            return StageResult.done(n + " doküman yüklendi", Map.of("documents", n));
        }
    }

    // ---------------------------------------------------------------- 2. Ayrıştırma
    static final class Parsing implements StageHandler {
        private final PipelineServices s;

        Parsing(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.PARSING; }

        public StageResult run(Review r) {
            int sections = 0, chars = 0, warnings = 0;
            for (ReviewDocument d : s.store().listDocuments(r.id())) {
                byte[] content = s.storage().read(d.storagePath());
                ParsedDocument p = s.parsing().parse(d.documentKey(), d.fileName(), d.contentType(), content,
                        d.clientText(), d.clientExtractionMethod());
                s.store().updateDocument(d.withParsed(p));
                sections += p.sections().size();
                chars += p.charCount();
                warnings += p.warnings().size();
            }
            String msg = sections + " bölüm, " + chars + " karakter" + (warnings > 0 ? ", " + warnings + " uyarı" : "");
            return StageResult.done(msg, Map.of("sections", sections, "chars", chars, "warnings", warnings));
        }
    }

    // ---------------------------------------------------------------- 3. Model çıkarımı (LLM)
    static final class ModelExtraction implements StageHandler {
        private final PipelineServices s;

        ModelExtraction(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.MODEL_EXTRACTION; }

        public StageResult run(Review r) {
            List<ParsedDocument> docs = s.store().listDocuments(r.id()).stream().map(ReviewDocument::parsed)
                    .filter(p -> p != null && !p.sections().isEmpty()).toList();
            if (docs.isEmpty()) {
                ModelExtractionOutput empty = new ModelExtractionOutput(
                        new com.bank.tm.core.model.SystemInfo(r.title(), ""), List.of(), List.of(), List.of(),
                        List.of("Dokümanlardan metin çıkarılamadı; model analist tarafından girilmeli."));
                s.store().putArtifact(r.id(), ArtifactKind.MODEL_EXTRACTION_OUTPUT, Json.write(empty));
                return StageResult.done("Metin yok; LLM çağrılmadı", Map.of("components", 0));
            }
            int maxChars = s.settings().maxChars(r.llmMode());
            StringBuilder text = new StringBuilder();
            boolean truncated = false;
            for (ParsedDocument d : docs) {
                for (ParsedDocument.Section sec : d.sections()) {
                    String block = "[" + d.documentKey() + "#" + sec.id() + "] " + sec.heading() + "\n" + sec.text() + "\n\n";
                    if (text.length() + block.length() > maxChars) {
                        truncated = true;
                        break;
                    }
                    text.append(block);
                }
            }
            PromptLibrary.Prompt p = s.prompts().get("model-extraction.v1");
            ObjectNode input = Json.MAPPER.createObjectNode();
            input.put("systemName", r.title());
            input.set("documents", Json.toTree(docs));
            input.set("intake", Json.toTree(r.intakeAnswers()));
            String note = analystNote(r);
            LlmRequest req = new LlmRequest(LlmTask.MODEL_EXTRACTION, p.version(), p.system(),
                    p.renderUser(Map.of("systemName", r.title(), "intake", Json.write(intakeView(r)),
                            "analystNote", note.isBlank() ? "-" : note, "documents", text.toString().strip())),
                    "model_extraction", SchemaRegistry.load(SchemaRegistry.MODEL_EXTRACTION), s.settings().temperature(),
                    null, input);
            LlmGateway.Outcome o = s.llm().call(r.id(), stage(), "model", r.llmMode(), req);
            if (o instanceof LlmGateway.Deferred d) {
                return StageResult.waitLlm("Model çıkarımı tarayıcı LLM'inde bekleniyor", Map.of("llmCallId", d.call().id()));
            }
            LlmGateway.Done done = (LlmGateway.Done) o;
            s.store().putArtifact(r.id(), ArtifactKind.MODEL_EXTRACTION_OUTPUT, Json.write(done.json()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("components", done.json().path("components").size());
            m.put("dataFlows", done.json().path("dataFlows").size());
            m.put("truncated", truncated);
            m.put("llmCallId", done.call().id());
            return StageResult.done(done.json().path("components").size() + " bileşen, "
                    + done.json().path("dataFlows").size() + " akış çıkarıldı" + (truncated ? " (metin kısaltıldı)" : ""), m);
        }

        private String analystNote(Review r) {
            Object note = r.intakeAnswers().get("analystNote");
            return note == null ? "" : note.toString();
        }

        private static Map<String, Object> intakeView(Review r) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("businessUnit", r.businessUnit());
            m.put("projectType", r.projectType());
            m.put("dataClassification", r.dataClassification());
            r.intakeAnswers().forEach((k, v) -> { if (!k.equals("analystNote")) m.put(k, v); });
            return m;
        }
    }

    // ---------------------------------------------------------------- 4. Model doğrulama
    static final class ModelValidation implements StageHandler {
        private final PipelineServices s;

        ModelValidation(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.MODEL_VALIDATION; }

        public StageResult run(Review r) {
            String raw = s.store().getArtifact(r.id(), ArtifactKind.MODEL_EXTRACTION_OUTPUT)
                    .orElseThrow(() -> new IllegalStateException("Model çıkarım çıktısı yok"));
            ModelExtractionOutput out = Json.read(raw, ModelExtractionOutput.class);
            List<ParsedDocument> docs = s.store().listDocuments(r.id()).stream().map(ReviewDocument::parsed)
                    .filter(p -> p != null).toList();
            ModelValidator.Result res = s.modelValidator().validate(out.toSystemModel(), docs);
            int version = s.store().listModelVersions(r.id()).size() + 1;
            s.store().insertModelVersion(new ModelVersion(Ids.uuid(), r.id(), version, "AI", res.model(), res.issues(),
                    "system", Clock.now(), null, null));
            long errors = res.issues().stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR).count();
            long warnings = res.issues().stream().filter(i -> i.severity() == ValidationIssue.Severity.WARNING).count();
            return StageResult.done("Model v" + version + ": " + errors + " düzeltme, " + warnings + " uyarı",
                    Map.of("version", version, "errors", errors, "warnings", warnings,
                            "missingInformation", res.model().missingInformation().size()));
        }
    }

    // ---------------------------------------------------------------- 5. Model onayı (analist)
    static final class ModelReview implements StageHandler {
        private final PipelineServices s;

        ModelReview(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.MODEL_REVIEW; }

        public StageResult run(Review r) {
            return s.store().approvedModel(r.id())
                    .map(m -> StageResult.done("Model v" + m.version() + " onaylandı (" + m.approvedBy() + ")",
                            Map.of("version", m.version())))
                    .orElseGet(() -> StageResult.waitUser("Analistin modeli onaylaması bekleniyor"));
        }
    }

    // ---------------------------------------------------------------- 6. Aday tehditler (kurallar)
    static final class Candidates implements StageHandler {
        private final PipelineServices s;

        Candidates(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.THREAT_CANDIDATES; }

        public StageResult run(Review r) {
            SystemModel model = approved(s, r).model();
            List<ThreatCandidate> list = s.candidates().generate(model, r);
            s.store().putArtifact(r.id(), ArtifactKind.THREAT_CANDIDATES, Json.write(list));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("candidates", list.size());
            m.put("rulesVersion", s.rules().threatRulesVersion());
            list.stream().collect(Collectors.groupingBy(c -> c.stride().name(), Collectors.counting()))
                    .forEach((k, v) -> m.put("stride." + k, v));
            return StageResult.done(list.size() + " aday tehdit (" + s.rules().threatRulesVersion() + ")", m);
        }
    }

    // ---------------------------------------------------------------- 7. Bilgi getirme (RAG)
    public record RetrievalHit(String id, double score, double lexical, double vector) {
    }

    public record RetrievalArtifact(String mode, Map<String, List<RetrievalHit>> perCandidate) {
    }

    static final class Retrieval implements StageHandler {
        private final PipelineServices s;

        Retrieval(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.KNOWLEDGE_RETRIEVAL; }

        public StageResult run(Review r) {
            List<ThreatCandidate> candidates = candidates(s, r);
            SystemModel model = approved(s, r).model();
            Map<String, List<RetrievalHit>> per = new LinkedHashMap<>();
            Set<String> unique = new LinkedHashSet<>();
            for (ThreatCandidate c : candidates) {
                Set<String> tags = new LinkedHashSet<>();
                tags.add(c.stride().name());
                model.component(c.targetId()).ifPresent(comp -> tags.add(comp.type().name()));
                model.flow(c.targetId()).ifPresent(f -> {
                    model.component(f.source()).ifPresent(x -> tags.add(x.type().name()));
                    model.component(f.target()).ifPresent(x -> tags.add(x.type().name()));
                });
                String query = c.ragQuery() + " " + c.title();
                List<RetrievedChunk> hits = s.retriever().search(new Retriever.Query(query, tags, null,
                        s.settings().retrievalPerCandidate(), s.settings().retrievalMaxPerSource()));
                List<RetrievalHit> list = hits.stream().map(h -> new RetrievalHit(h.chunk().id(), h.score(),
                        h.lexicalScore(), h.vectorScore())).toList();
                list.forEach(h -> unique.add(h.id()));
                per.put(c.id(), list);
            }
            s.store().putArtifact(r.id(), ArtifactKind.KNOWLEDGE_RETRIEVAL,
                    Json.write(new RetrievalArtifact(s.retriever().mode(), per)));
            return StageResult.done(unique.size() + " farklı kaynak getirildi (" + s.retriever().mode() + ")",
                    Map.of("uniqueSources", unique.size(), "candidates", candidates.size()));
        }
    }

    // ---------------------------------------------------------------- 8. Tehdit üretimi (LLM)
    public record GenerationBatch(String key, String llmCallId, List<String> candidateIds, List<LlmThreat> threats) {
    }

    static final class ThreatGeneration implements StageHandler {
        private final PipelineServices s;

        ThreatGeneration(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.THREAT_GENERATION; }

        public StageResult run(Review r) {
            List<ThreatCandidate> candidates = candidates(s, r);
            RetrievalArtifact retrieval = retrieval(s, r);
            SystemModel model = approved(s, r).model();
            int size = s.settings().batchSize(r.llmMode());
            int total = (candidates.size() + size - 1) / size;
            List<GenerationBatch> batches = new ArrayList<>();
            PromptLibrary.Prompt p = s.prompts().get("threat-generation.v1");
            JsonNode schema = SchemaRegistry.load(SchemaRegistry.THREAT_GENERATION);
            String modelJson = Json.write(compactModel(model));
            for (int i = 0; i < total; i++) {
                List<ThreatCandidate> batch = candidates.subList(i * size, Math.min(candidates.size(), (i + 1) * size));
                List<Map<String, Object>> sources = sourcesFor(batch, retrieval);
                ObjectNode input = Json.MAPPER.createObjectNode();
                input.set("model", Json.toTree(model));
                input.set("candidates", Json.toTree(batch));
                input.set("sources", Json.toTree(sources));
                LlmRequest req = new LlmRequest(LlmTask.THREAT_GENERATION, p.version(), p.system(),
                        p.renderUser(Map.of("model", modelJson, "candidates", Json.write(candidateView(batch)),
                                "sources", sourcesText(sources))),
                        "threat_generation", schema, s.settings().temperature(), null, input);
                String key = "batch-" + (i + 1) + "/" + total;
                LlmGateway.Outcome o = s.llm().call(r.id(), stage(), key, r.llmMode(), req);
                if (o instanceof LlmGateway.Deferred d) {
                    return StageResult.waitLlm("Tehdit üretimi " + (i + 1) + "/" + total + " tarayıcı LLM'inde bekleniyor",
                            Map.of("batch", i + 1, "batches", total, "llmCallId", d.call().id()));
                }
                LlmGateway.Done done = (LlmGateway.Done) o;
                ThreatGenerationOutput out = Json.MAPPER.convertValue(done.json(), ThreatGenerationOutput.class);
                batches.add(new GenerationBatch(key, done.call().id(), batch.stream().map(ThreatCandidate::id).toList(),
                        out.threats()));
            }
            s.store().putArtifact(r.id(), ArtifactKind.THREAT_GENERATION_OUTPUT, Json.write(batches));
            int threats = batches.stream().mapToInt(b -> b.threats().size()).sum();
            return StageResult.done(threats + " tehdit önerisi (" + total + " çağrı)",
                    Map.of("batches", total, "llmThreats", threats));
        }

        private static Map<String, Object> compactModel(SystemModel m) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("system", m.system().name());
            out.put("components", m.components().stream().map(c -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", c.id());
                x.put("name", c.name());
                x.put("type", c.type());
                x.put("technology", c.technology());
                x.put("zone", c.zone());
                x.put("internetFacing", c.internetFacing());
                x.put("authentication", c.authentication());
                x.put("dataClasses", c.dataClasses());
                return x;
            }).toList());
            out.put("dataFlows", m.dataFlows().stream().map(f -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", f.id());
                x.put("source", f.source());
                x.put("target", f.target());
                x.put("protocol", f.protocol());
                x.put("authentication", f.authentication());
                x.put("encrypted", f.encrypted());
                x.put("crossesTrustBoundary", f.crossesTrustBoundary());
                x.put("dataClasses", f.dataClasses());
                return x;
            }).toList());
            return out;
        }

        private static List<Map<String, Object>> candidateView(List<ThreatCandidate> batch) {
            return batch.stream().map(c -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", c.id());
                x.put("stride", c.stride());
                x.put("targetId", c.targetId());
                x.put("target", c.targetName());
                x.put("title", c.title());
                x.put("description", c.description());
                return x;
            }).toList();
        }

        private List<Map<String, Object>> sourcesFor(List<ThreatCandidate> batch, RetrievalArtifact retrieval) {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (ThreatCandidate c : batch) {
                for (RetrievalHit h : retrieval.perCandidate().getOrDefault(c.id(), List.of())) {
                    KnowledgeChunk k = s.knowledgeBase().get(h.id()).orElse(null);
                    if (k == null) continue;
                    Map<String, Object> m = out.computeIfAbsent(k.id(), id -> {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("id", k.id());
                        x.put("source", k.source());
                        x.put("title", k.title());
                        x.put("text", k.text());
                        x.put("mitigation", k.mitigation());
                        x.put("candidateIds", new ArrayList<String>());
                        return x;
                    });
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) m.get("candidateIds");
                    ids.add(c.id());
                }
            }
            return new ArrayList<>(out.values());
        }

        private static String sourcesText(List<Map<String, Object>> sources) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> m : sources) {
                sb.append(m.get("id")).append(" | ").append(m.get("source")).append(" | ").append(m.get("title"))
                        .append(" | ").append(Text.truncate(String.valueOf(m.get("mitigation")), 240))
                        .append(" | ilgili adaylar: ").append(m.get("candidateIds")).append('\n');
            }
            return sb.toString().strip();
        }
    }

    // ---------------------------------------------------------------- 9. Tehdit doğrulama
    static final class ThreatValidation implements StageHandler {
        private final PipelineServices s;

        ThreatValidation(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.THREAT_VALIDATION; }

        public StageResult run(Review r) {
            ModelVersion mv = approved(s, r);
            List<ThreatCandidate> candidates = candidates(s, r);
            RetrievalArtifact retrieval = retrieval(s, r);
            List<GenerationBatch> batches = Json.read(s.store().getArtifact(r.id(), ArtifactKind.THREAT_GENERATION_OUTPUT)
                    .orElseThrow(), new TypeReference<>() { });
            Map<String, KnowledgeChunk> allowed = new LinkedHashMap<>();
            retrieval.perCandidate().values().forEach(l -> l.forEach(h -> s.knowledgeBase().get(h.id())
                    .ifPresent(k -> allowed.put(k.id(), k))));
            Map<String, String> callByCandidate = new LinkedHashMap<>();
            List<LlmThreat> all = new ArrayList<>();
            for (GenerationBatch b : batches) {
                all.addAll(b.threats());
                b.threats().forEach(t -> { if (t.candidateId() != null) callByCandidate.put(t.candidateId(), b.llmCallId()); });
            }
            ThreatValidator.Result res = s.threatValidator().validate(all, candidates, mv.model(), allowed);
            List<ThreatRecord> records = new ArrayList<>();
            int n = 1;
            for (ThreatValidator.ValidatedThreat v : res.threats()) {
                LlmThreat t = v.threat();
                List<Mitigation> mitigations = t.mitigations().stream()
                        .map(m -> new Mitigation(m.title(), m.description(), m.sourceIds(), "AI")).toList();
                Set<String> srcIds = new LinkedHashSet<>(t.sourceIds());
                t.mitigations().forEach(m -> srcIds.addAll(m.sourceIds()));
                List<SourceRef> sources = srcIds.stream().map(allowed::get).filter(k -> k != null)
                        .map(k -> new SourceRef(k.id(), k.source(), k.title(), k.reference())).toList();
                AiAssessment ai = new AiAssessment(t.title(), t.description(), t.attackScenario(),
                        t.suggestedLikelihood(), t.suggestedImpact(), t.riskRationale(), mitigations, t.confidence(),
                        t.assumptions(), t.applicable(), t.notApplicableReason(),
                        v.candidate() == null ? null : callByCandidate.get(v.candidate().id()), v.fromRuleTemplate());
                records.add(new ThreatRecord(Ids.uuid(), r.id(), "T" + n++, mv.version(),
                        v.candidate() == null ? null : v.candidate().id(),
                        v.candidate() == null ? null : v.candidate().ruleId(), t.strideCategory(), t.targetId(),
                        mv.model().elementName(t.targetId()), v.aiAdditional(), ai, null, ExpertAssessment.empty(),
                        sources, v.issues(), Clock.now(), Clock.now()));
            }
            s.store().replaceThreats(r.id(), records);
            Map<String, Object> report = new LinkedHashMap<>(res.metrics());
            report.put("issues", res.issues());
            s.store().putArtifact(r.id(), ArtifactKind.THREAT_VALIDATION_REPORT, Json.write(report));
            Map<String, Object> m = new LinkedHashMap<>(res.metrics());
            return StageResult.done(records.size() + " tehdit; " + res.metrics().get("duplicatesRemoved") + " tekrar, "
                    + res.metrics().get("unknownSourcesRemoved") + " geçersiz kaynak çıkarıldı", m);
        }
    }

    // ---------------------------------------------------------------- 10. Risk değerlendirmesi
    static final class RiskAssessment implements StageHandler {
        private final PipelineServices s;

        RiskAssessment(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.RISK_ASSESSMENT; }

        public StageResult run(Review r) {
            SystemModel model = approved(s, r).model();
            Map<String, ThreatCandidate> byId = new LinkedHashMap<>();
            candidates(s, r).forEach(c -> byId.put(c.id(), c));
            Map<String, Object> levels = new LinkedHashMap<>();
            for (ThreatRecord t : s.store().listThreats(r.id())) {
                ThreatCandidate c = t.candidateId() == null ? null : byId.get(t.candidateId());
                Map<String, Object> facts = Facts.forThreat(model, t.targetId(), t.stride(), t.ruleId(), t.aiAdditional(), r);
                SystemAssessment a = s.risk().assess(t.stride(), c == null ? null : c.defaultLikelihood(),
                        c == null ? null : c.defaultImpact(), facts);
                s.store().updateThreat(t.withSystem(a, Clock.now()));
                levels.merge("level." + a.level(), 1, (x, y) -> (Integer) x + (Integer) y);
            }
            s.store().putArtifact(r.id(), ArtifactKind.RISK_SUMMARY, Json.write(levels));
            levels.put("methodology", s.rules().methodology().version());
            levels.put("riskRules", s.rules().riskRulesVersion());
            return StageResult.done("Risk skorları hesaplandı (" + s.rules().methodology().version() + ")", levels);
        }
    }

    // ---------------------------------------------------------------- 11. Uzman incelemesi (analist)
    static final class ExpertReview implements StageHandler {
        private final PipelineServices s;

        ExpertReview(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.EXPERT_REVIEW; }

        public StageResult run(Review r) {
            if (r.expertReviewCompleted()) {
                return StageResult.done("Uzman incelemesi tamamlandı", Map.of());
            }
            return StageResult.waitUser("Tehditlerin uzman tarafından incelenmesi bekleniyor");
        }
    }

    // ---------------------------------------------------------------- 12. Rapor
    static final class Report implements StageHandler {
        private final PipelineServices s;

        Report(PipelineServices s) { this.s = s; }

        public Stage stage() { return Stage.REPORT; }

        public StageResult run(Review r) {
            s.store().putArtifact(r.id(), ArtifactKind.REPORT_SNAPSHOT,
                    Json.write(new com.bank.tm.core.service.ReportBuilder(s.store(), s.rules()).build(r.id())));
            return StageResult.done("Rapor oluşturuldu", Map.of());
        }
    }

    // ---------------------------------------------------------------- yardımcılar
    static ModelVersion approved(PipelineServices s, Review r) {
        return s.store().approvedModel(r.id()).orElseThrow(() -> new IllegalStateException("Onaylı model yok"));
    }

    static List<ThreatCandidate> candidates(PipelineServices s, Review r) {
        return Json.read(s.store().getArtifact(r.id(), ArtifactKind.THREAT_CANDIDATES)
                .orElseThrow(() -> new IllegalStateException("Aday tehditler yok")), new TypeReference<>() { });
    }

    static RetrievalArtifact retrieval(PipelineServices s, Review r) {
        return Json.read(s.store().getArtifact(r.id(), ArtifactKind.KNOWLEDGE_RETRIEVAL)
                .orElseThrow(() -> new IllegalStateException("Bilgi getirme çıktısı yok")), RetrievalArtifact.class);
    }
}
