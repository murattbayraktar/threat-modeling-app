package com.bank.tm.core.validation;

import com.bank.tm.core.model.Stride;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.model.ThreatCandidate;
import com.bank.tm.core.model.ThreatGenerationOutput.LlmMitigation;
import com.bank.tm.core.model.ThreatGenerationOutput.LlmThreat;
import com.bank.tm.core.rag.KnowledgeChunk;
import com.bank.tm.core.rag.Tokenizer;
import com.bank.tm.core.util.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM tehdit çıktısını kullanıcıya göstermeden önce doğrular:
 * zorunlu alanlar, hedefin modelde olması, aday kimliğinin geçerliliği, kaynak kimliklerinin gerçekten
 * getirilmiş kaynaklardan olması, tekrar eden tehditler ve önlem–tehdit kategorisi uyumu.
 * LLM'in atladığı adaylar kural şablonuyla tamamlanır (hiçbir aday sessizce kaybolmaz).
 */
public class ThreatValidator {

    public record ValidatedThreat(LlmThreat threat, ThreatCandidate candidate, boolean aiAdditional,
                                  boolean fromRuleTemplate, List<ValidationIssue> issues) {
    }

    public record Result(List<ValidatedThreat> threats, List<ValidationIssue> issues, Map<String, Integer> metrics) {
    }

    private static final double DUPLICATE_JACCARD = 0.6;

    /**
     * @param allowedSources tehdit üretimi sırasında LLM'e verilen kaynaklar (id → kayıt)
     */
    public Result validate(List<LlmThreat> output, List<ThreatCandidate> candidates, SystemModel model,
                           Map<String, KnowledgeChunk> allowedSources) {
        Map<String, ThreatCandidate> byId = new LinkedHashMap<>();
        candidates.forEach(c -> byId.put(c.id(), c));
        List<ValidationIssue> global = new ArrayList<>();
        List<ValidatedThreat> accepted = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        int invalidTarget = 0, unknownSources = 0, duplicates = 0, incomplete = 0, notApplicable = 0, additional = 0,
                mismatchedMitigations = 0;

        for (LlmThreat t : output) {
            List<ValidationIssue> issues = new ArrayList<>();
            ThreatCandidate cand = t.candidateId() == null ? null : byId.get(t.candidateId());
            if (t.candidateId() != null && cand == null) {
                issues.add(ValidationIssue.warning("UNKNOWN_CANDIDATE", t.candidateId(),
                        "LLM tanımsız bir aday kimliği döndürdü; ek öneri olarak işaretlendi."));
            }
            if (cand != null && !covered.add(cand.id())) {
                global.add(ValidationIssue.error("DUPLICATE_CANDIDATE", cand.id(), "Aynı aday için ikinci çıktı çıkarıldı."));
                duplicates++;
                continue;
            }
            String targetId = t.targetId();
            if (!model.hasElement(targetId)) {
                if (cand != null) {
                    issues.add(ValidationIssue.error("INVALID_TARGET", targetId,
                            "Hedef modelde yok; adayın hedefi (" + cand.targetId() + ") kullanıldı."));
                    targetId = cand.targetId();
                } else {
                    global.add(ValidationIssue.error("INVALID_TARGET", targetId, "Modelde olmayan öğeye ait tehdit çıkarıldı: " + t.title()));
                    invalidTarget++;
                    continue;
                }
            }
            Stride stride = t.strideCategory();
            if (cand != null && stride != cand.stride()) {
                issues.add(ValidationIssue.warning("STRIDE_CHANGED", cand.id(),
                        "LLM STRIDE kategorisini " + cand.stride() + " → " + stride + " olarak değiştirdi; aday kategorisi korundu."));
                stride = cand.stride();
            }
            if (Text.isBlank(t.title()) || Text.isBlank(t.description())) {
                if (cand == null) {
                    global.add(ValidationIssue.error("INCOMPLETE", null, "Başlığı veya açıklaması boş ek öneri çıkarıldı."));
                    incomplete++;
                    continue;
                }
                issues.add(ValidationIssue.warning("INCOMPLETE", cand.id(), "Eksik başlık/açıklama kural şablonuyla dolduruldu."));
            }
            // Kaynaklar
            List<String> sourceIds = filterSources(t.sourceIds(), allowedSources, issues);
            List<LlmMitigation> mitigations = new ArrayList<>();
            for (LlmMitigation m : t.mitigations()) {
                if (Text.isBlank(m.title()) && Text.isBlank(m.description())) continue;
                List<String> ms = filterSources(m.sourceIds(), allowedSources, issues);
                final Stride cat = stride;
                if (!ms.isEmpty() && ms.stream().noneMatch(id -> tagged(allowedSources.get(id), cat))) {
                    issues.add(ValidationIssue.warning("MITIGATION_MISMATCH", null, "'" + m.title()
                            + "' önleminin kaynakları " + stride + " kategorisiyle etiketli değil; uygunluğunu kontrol edin."));
                    mismatchedMitigations++;
                }
                mitigations.add(new LlmMitigation(m.title(), m.description(), ms));
            }
            unknownSources += (int) issues.stream().filter(i -> i.code().equals("UNKNOWN_SOURCE")).count();
            if (mitigations.isEmpty() && t.applicable()) {
                issues.add(ValidationIssue.warning("NO_MITIGATION", null, "Önlem önerisi yok; uzman eklemeli."));
            }

            LlmThreat fixed = new LlmThreat(cand == null ? null : cand.id(), t.applicable(), t.notApplicableReason(),
                    Text.isBlank(t.title()) ? cand.title() : t.title(),
                    Text.isBlank(t.description()) ? cand.description() : t.description(),
                    t.attackScenario(), stride, targetId, clamp(t.suggestedLikelihood()), clamp(t.suggestedImpact()),
                    t.riskRationale(), mitigations, sourceIds, t.confidence(), t.assumptions());

            // Tekrar kontrolü: aynı hedef + kategori + benzer başlık
            ValidatedThreat dup = accepted.stream().filter(a -> a.threat().targetId().equals(fixed.targetId())
                    && a.threat().strideCategory() == fixed.strideCategory()
                    && jaccard(a.threat().title(), fixed.title()) >= DUPLICATE_JACCARD).findFirst().orElse(null);
            if (dup != null && cand == null) {
                global.add(ValidationIssue.error("DUPLICATE", null, "'" + fixed.title() + "' mevcut bir tehdidin tekrarı; çıkarıldı."));
                duplicates++;
                continue;
            }
            if (dup != null) {
                issues.add(ValidationIssue.warning("SIMILAR_THREAT", dup.threat().candidateId(),
                        "Benzer bir tehdit zaten var; birleştirmeyi değerlendirin."));
            }
            if (!t.applicable()) notApplicable++;
            if (cand == null) additional++;
            accepted.add(new ValidatedThreat(fixed, cand, cand == null, false, issues));
        }

        // LLM'in döndürmediği adaylar
        int filled = 0;
        for (ThreatCandidate c : candidates) {
            if (covered.contains(c.id())) continue;
            filled++;
            LlmThreat t = new LlmThreat(c.id(), true, null, c.title(), c.description(), "", c.stride(), c.targetId(),
                    c.defaultLikelihood(), c.defaultImpact(), "LLM bu adayı döndürmedi; kural şablonu kullanıldı.",
                    List.of(), List.of(), com.bank.tm.core.model.Confidence.LOW, List.of());
            accepted.add(new ValidatedThreat(t, c, false, true, List.of(ValidationIssue.warning("CANDIDATE_NOT_RETURNED",
                    c.id(), "LLM bu adayı döndürmedi; kural şablonu kullanıldı."))));
        }

        Map<String, Integer> metrics = new LinkedHashMap<>();
        metrics.put("llmThreats", output.size());
        metrics.put("accepted", accepted.size());
        metrics.put("duplicatesRemoved", duplicates);
        metrics.put("invalidTargetsRemoved", invalidTarget);
        metrics.put("incompleteRemoved", incomplete);
        metrics.put("unknownSourcesRemoved", unknownSources);
        metrics.put("mitigationMismatches", mismatchedMitigations);
        metrics.put("notApplicable", notApplicable);
        metrics.put("aiAdditional", additional);
        metrics.put("candidatesFilledFromRules", filled);
        return new Result(accepted, global, metrics);
    }

    private static List<String> filterSources(List<String> ids, Map<String, KnowledgeChunk> allowed,
                                              List<ValidationIssue> issues) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (allowed.containsKey(id)) {
                if (!out.contains(id)) out.add(id);
            } else {
                issues.add(ValidationIssue.error("UNKNOWN_SOURCE", id, "Verilen kaynaklar arasında olmayan referans çıkarıldı: " + id));
            }
        }
        return out;
    }

    private static boolean tagged(KnowledgeChunk c, Stride s) {
        return c != null && (c.tags().contains(s.name()) || "BANK_CONTROL".equals(c.source()) && c.tags().isEmpty());
    }

    private static int clamp(int v) {
        return Math.max(1, Math.min(5, v));
    }

    static double jaccard(String a, String b) {
        Set<String> x = new HashSet<>(Tokenizer.tokens(a));
        Set<String> y = new HashSet<>(Tokenizer.tokens(b));
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }
}
