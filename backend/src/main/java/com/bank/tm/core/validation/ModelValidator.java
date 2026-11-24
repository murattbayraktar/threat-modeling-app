package com.bank.tm.core.validation;

import com.bank.tm.core.model.Component;
import com.bank.tm.core.model.Confidence;
import com.bank.tm.core.model.DataFlow;
import com.bank.tm.core.model.Evidence;
import com.bank.tm.core.model.MissingInformation;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.model.TrustBoundary;
import com.bank.tm.core.model.Zone;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM'in çıkardığı (veya analistin düzenlediği) modeli doğrular ve zenginleştirir:
 * - Tekrar eden kimlikler, geçersiz akış uçları, kendine dönen akışlar (ERROR: öğe düzeltilir/çıkarılır)
 * - Kanıt alıntısının dokümanda gerçekten geçip geçmediği (WARNING + güven LOW): uydurma öğeleri yakalar
 * - Türetilen alanlar: elementKind, crossesTrustBoundary, trustBoundaries (LLM'e bırakılmaz)
 */
public class ModelValidator {

    public record Result(SystemModel model, List<ValidationIssue> issues) {
    }

    public Result validate(SystemModel input, List<ParsedDocument> documents) {
        return validate(input, documents, false);
    }

    /** @param analystEdited true ise kanıtı olmayan öğeler analistin eklediği kabul edilir (uyarı yerine bilgi). */
    public Result validate(SystemModel input, List<ParsedDocument> documents, boolean analystEdited) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, String> corpus = corpus(documents);

        // Bileşenler
        Map<String, Component> comps = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        int next = input.components().size() + 1;
        for (Component c : input.components()) {
            String id = c.id();
            if (id == null || id.isBlank() || comps.containsKey(id)) {
                String newId = "C" + next++;
                issues.add(ValidationIssue.error("DUPLICATE_ID", id, "Tekrar eden veya boş bileşen kimliği " + newId + " olarak değiştirildi."));
                c = new Component(newId, c.name(), c.type(), c.technology(), c.zone(), c.internetFacing(),
                        c.authentication(), c.dataClasses(), c.description(), c.evidence(), c.confidence(), null);
                id = newId;
            }
            if (c.name() == null || c.name().isBlank()) {
                issues.add(ValidationIssue.error("MISSING_NAME", id, "Adı olmayan bileşen çıkarıldı."));
                continue;
            }
            if (!names.add(Text.fold(c.name()))) {
                issues.add(ValidationIssue.warning("DUPLICATE_NAME", id, "'" + c.name() + "' adıyla birden fazla bileşen var."));
            }
            if (analystEdited && noEvidence(c.evidence())) {
                issues.add(ValidationIssue.info("ANALYST_ADDED", id, "'" + c.name() + "' analist tarafından eklendi (doküman kanıtı yok)."));
            } else if (!evidenceFound(c.evidence(), corpus)) {
                issues.add(ValidationIssue.warning("EVIDENCE_NOT_FOUND", id, "'" + c.name()
                        + "' için verilen kanıt alıntısı dokümanlarda bulunamadı; öğeyi doğrulayın."));
                c = c.withConfidence(Confidence.LOW);
            }
            if (c.zone() == Zone.UNKNOWN) {
                issues.add(ValidationIssue.info("UNKNOWN_ZONE", id, c.name() + " için ağ bölgesi bilinmiyor."));
            }
            comps.put(id, c.withElementKind(c.type().elementKind()));
        }

        // Akışlar
        List<DataFlow> flows = new ArrayList<>();
        Set<String> flowIds = new HashSet<>();
        int nextFlow = input.dataFlows().size() + 1;
        for (DataFlow f : input.dataFlows()) {
            if (!comps.containsKey(f.source()) || !comps.containsKey(f.target())) {
                issues.add(ValidationIssue.error("INVALID_FLOW_ENDPOINT", f.id(), "Akış (" + f.source() + " → "
                        + f.target() + ") modelde olmayan bir bileşene işaret ediyor; çıkarıldı."));
                continue;
            }
            if (f.source().equals(f.target())) {
                issues.add(ValidationIssue.error("SELF_FLOW", f.id(), "Kaynak ve hedefi aynı olan akış çıkarıldı."));
                continue;
            }
            String id = f.id();
            if (id == null || id.isBlank() || !flowIds.add(id)) {
                id = "F" + nextFlow++;
                flowIds.add(id);
                issues.add(ValidationIssue.error("DUPLICATE_ID", f.id(), "Tekrar eden akış kimliği " + id + " olarak değiştirildi."));
                f = new DataFlow(id, f.source(), f.target(), f.description(), f.protocol(), f.authentication(),
                        f.encrypted(), f.dataClasses(), f.evidence(), f.confidence(), null);
            }
            if (analystEdited && noEvidence(f.evidence())) {
                issues.add(ValidationIssue.info("ANALYST_ADDED", id, "Akış analist tarafından eklendi (doküman kanıtı yok)."));
            } else if (!evidenceFound(f.evidence(), corpus)) {
                issues.add(ValidationIssue.warning("EVIDENCE_NOT_FOUND", id, "Akış için verilen kanıt alıntısı dokümanlarda bulunamadı."));
                f = f.withConfidence(Confidence.LOW);
            }
            Zone a = comps.get(f.source()).zone();
            Zone b = comps.get(f.target()).zone();
            Boolean crosses = (a == Zone.UNKNOWN || b == Zone.UNKNOWN) ? null : a != b;
            flows.add(f.withCrossesTrustBoundary(crosses));
        }

        // Güven sınırları (bölgeye göre)
        Map<Zone, List<String>> byZone = new LinkedHashMap<>();
        comps.values().forEach(c -> byZone.computeIfAbsent(c.zone(), z -> new ArrayList<>()).add(c.id()));
        List<TrustBoundary> boundaries = new ArrayList<>();
        int b = 1;
        for (Map.Entry<Zone, List<String>> e : byZone.entrySet()) {
            if (e.getKey() != Zone.UNKNOWN) boundaries.add(new TrustBoundary("B" + b++, e.getKey(), e.getValue()));
        }

        // Eksik bilgi soruları geçerli öğelere işaret etmeli
        List<MissingInformation> missing = new ArrayList<>();
        Set<String> valid = new HashSet<>(comps.keySet());
        flows.forEach(f -> valid.add(f.id()));
        for (MissingInformation m : input.missingInformation()) {
            missing.add(m.relatedElementId() == null || valid.contains(m.relatedElementId()) ? m
                    : new MissingInformation(m.question(), null));
        }
        if (comps.isEmpty()) {
            issues.add(ValidationIssue.error("EMPTY_MODEL", null, "Dokümanlardan hiç bileşen çıkarılamadı. Bileşenleri model ekranında elle ekleyin."));
        }
        SystemModel out = input.withParts(new ArrayList<>(comps.values()), flows, boundaries, missing);
        return new Result(out, issues);
    }

    private static boolean noEvidence(Evidence e) {
        return e == null || e.quote() == null || e.quote().isBlank();
    }

    private static Map<String, String> corpus(List<ParsedDocument> docs) {
        Map<String, String> m = new HashMap<>();
        StringBuilder all = new StringBuilder();
        for (ParsedDocument d : docs) {
            StringBuilder sb = new StringBuilder();
            d.sections().forEach(s -> sb.append(s.heading()).append('\n').append(s.text()).append('\n'));
            m.put(d.documentKey(), norm(sb.toString()));
            all.append(sb);
        }
        m.put("*", norm(all.toString()));
        return m;
    }

    private static boolean evidenceFound(Evidence e, Map<String, String> corpus) {
        if (e == null || e.quote() == null || e.quote().isBlank()) return false;
        String q = norm(e.quote());
        if (q.length() > 160) q = q.substring(0, 160);
        String doc = corpus.getOrDefault(e.documentId(), corpus.get("*"));
        return doc.contains(q) || corpus.get("*").contains(q);
    }

    private static String norm(String s) {
        return Text.fold(s).replaceAll("[\"'“”‘’`]", "").replaceAll("\\s+", " ").strip();
    }
}
