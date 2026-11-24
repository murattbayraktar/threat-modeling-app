package com.bank.tm.core.rag;

import com.bank.tm.core.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hibrit arama: BM25 (her zaman) + vektör benzerliği (embedding sağlayıcı tanımlıysa).
 * Etiket eşleşmeleri (STRIDE kategorisi, bileşen türü) skoru artırır. LLM çağrılmadan çalışır; bilgi getirme
 * ekranı ve tehdit üretimi için bağlam toplama aynı servisi kullanır.
 */
public class Retriever {

    public record Query(String text, Set<String> boostTags, Set<String> sources, int limit, int maxPerSource) {
    }

    private final KnowledgeBase kb;
    private final EmbeddingProvider embeddings;
    private volatile Bm25Index index;
    private volatile List<KnowledgeChunk> indexed = List.of();
    private final Map<String, float[]> vectorCache = new ConcurrentHashMap<>();

    public Retriever(KnowledgeBase kb, EmbeddingProvider embeddings) {
        this.kb = kb;
        this.embeddings = embeddings;
        rebuild();
    }

    public synchronized void rebuild() {
        indexed = kb.all();
        index = new Bm25Index(indexed);
        vectorCache.clear();
    }

    private boolean vectorsOn() {
        return embeddings != null && embeddings.available();
    }

    /** Embedding sağlayıcısı/modeli değişince önbellekteki vektörler geçersiz olur. */
    public void clearVectorCache() {
        vectorCache.clear();
    }

    public String mode() {
        return !vectorsOn() ? "BM25" : "HYBRID(BM25+" + embeddings.name() + ")";
    }

    public List<RetrievedChunk> search(Query q) {
        if (indexed != kb.all()) rebuild();
        List<KnowledgeChunk> docs = indexed;
        double[] lexical = index.scores(q.text());
        double maxLex = 0;
        for (double v : lexical) maxLex = Math.max(maxLex, v);
        double[] vector = vectorScores(q.text(), docs);

        List<RetrievedChunk> scored = new ArrayList<>();
        Set<String> boost = q.boostTags() == null ? Set.of() : foldAll(q.boostTags());
        for (int i = 0; i < docs.size(); i++) {
            KnowledgeChunk c = docs.get(i);
            if (q.sources() != null && !q.sources().isEmpty() && !q.sources().contains(c.source())) continue;
            double lex = maxLex > 0 ? lexical[i] / maxLex : 0;
            double vec = vector == null ? 0 : vector[i];
            double base = vector == null ? lex : 0.6 * lex + 0.4 * vec;
            if (base <= 0) continue;
            long tagHits = c.tags().stream().map(Text::fold).filter(boost::contains).count();
            double score = base * (1 + 0.25 * Math.min(tagHits, 2));
            scored.add(new RetrievedChunk(c, round(score), round(lex), round(vec)));
        }
        scored.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        List<RetrievedChunk> out = new ArrayList<>();
        Map<String, Integer> perSource = new HashMap<>();
        int maxPer = q.maxPerSource() <= 0 ? Integer.MAX_VALUE : q.maxPerSource();
        for (RetrievedChunk r : scored) {
            if (out.size() >= q.limit()) break;
            int n = perSource.getOrDefault(r.chunk().source(), 0);
            if (n >= maxPer) continue;
            perSource.put(r.chunk().source(), n + 1);
            out.add(r);
        }
        return out;
    }

    private double[] vectorScores(String query, List<KnowledgeChunk> docs) {
        if (!vectorsOn()) return null;
        try {
            float[] qv = embeddings.embed(query);
            double[] s = new double[docs.size()];
            for (int i = 0; i < docs.size(); i++) {
                KnowledgeChunk c = docs.get(i);
                float[] dv = vectorCache.get(c.id());
                if (dv == null) {
                    dv = embeddings.embed(c.searchableText());
                    vectorCache.put(c.id(), dv);
                }
                s[i] = Math.max(0, cosine(qv, dv));
            }
            return s;
        } catch (Exception e) {
            return null; // vektör katmanı erişilemezse BM25 ile devam
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static Set<String> foldAll(Set<String> s) {
        Set<String> out = new java.util.HashSet<>();
        s.forEach(x -> out.add(Text.fold(x)));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
