package com.bank.tm.core.service;

import com.bank.tm.core.domain.Review;
import com.bank.tm.core.domain.ThreatRecord;
import com.bank.tm.core.store.ReviewStore;
import com.bank.tm.core.util.Text;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Örnek projelerden oluşturulan incelemelerde üretilen tehditleri uzman referans listesiyle karşılaştırır.
 * Eşleşme: aynı STRIDE kategorisi + aynı hedef (bileşen adı veya "Kaynak → Hedef").
 * Bu ölçüm model kalitesini izlemek içindir; tek başına güvenlik etkinliğinin kanıtı değildir.
 */
public class EvaluationService {

    public record ExpectedMatch(String id, String stride, String target, String note, String matchedThreatCode) {
    }

    public record Evaluation(String sample, int expected, int matched, double recall, int generated,
                             int unmatchedGenerated, List<ExpectedMatch> details, List<String> unmatchedThreats) {
    }

    private final ReviewStore store;
    private final SampleLibrary samples;

    public EvaluationService(ReviewStore store, SampleLibrary samples) {
        this.store = store;
        this.samples = samples;
    }

    public Evaluation evaluate(String reviewId) {
        Review r = store.findReview(reviewId).orElseThrow(() -> new NotFoundException("İnceleme yok"));
        if (r.sampleName() == null) throw new ConflictException("Bu inceleme bir örnek projeden oluşturulmadı; referans liste yok.");
        JsonNode expected = samples.expected(r.sampleName());
        if (expected == null) throw new NotFoundException("Örnek için değerlendirme dosyası yok: " + r.sampleName());
        List<ThreatRecord> threats = store.listThreats(reviewId);
        Set<String> used = new HashSet<>();
        List<ExpectedMatch> details = new ArrayList<>();
        int matched = 0;
        for (JsonNode e : expected.path("expectedThreats")) {
            String stride = e.path("stride").asText();
            String target = e.path("target").asText();
            ThreatRecord hit = threats.stream()
                    .filter(t -> t.stride().name().equals(stride) && norm(t.targetName()).equals(norm(target)))
                    .findFirst().orElse(null);
            if (hit != null) {
                matched++;
                used.add(hit.id());
            }
            details.add(new ExpectedMatch(e.path("id").asText(), stride, target, e.path("note").asText(),
                    hit == null ? null : hit.code()));
        }
        List<String> unmatched = threats.stream().filter(t -> !used.contains(t.id()))
                .map(t -> t.code() + " " + t.stride() + " · " + t.targetName()).toList();
        int total = details.size();
        return new Evaluation(r.sampleName(), total, matched, total == 0 ? 0 : Math.round(1000.0 * matched / total) / 1000.0,
                threats.size(), unmatched.size(), details, unmatched);
    }

    private static String norm(String s) {
        return Text.fold(s).replace("->", "→").replaceAll("\\s+", " ").strip();
    }
}
