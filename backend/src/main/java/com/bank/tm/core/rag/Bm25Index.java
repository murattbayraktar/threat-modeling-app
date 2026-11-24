package com.bank.tm.core.rag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bellek içi BM25 sözcüksel indeks. Kontrol kodları, CWE numaraları gibi kesin terimlerde güçlüdür. */
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<KnowledgeChunk> docs;
    private final List<Map<String, Integer>> termFreqs;
    private final int[] lengths;
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final double avgLength;

    public Bm25Index(List<KnowledgeChunk> docs) {
        this.docs = List.copyOf(docs);
        this.termFreqs = new java.util.ArrayList<>();
        this.lengths = new int[docs.size()];
        long total = 0;
        for (int i = 0; i < docs.size(); i++) {
            List<String> toks = Tokenizer.tokens(docs.get(i).searchableText());
            Map<String, Integer> tf = new HashMap<>();
            toks.forEach(t -> tf.merge(t, 1, Integer::sum));
            termFreqs.add(tf);
            lengths[i] = toks.size();
            total += toks.size();
            tf.keySet().forEach(t -> docFreq.merge(t, 1, Integer::sum));
        }
        avgLength = docs.isEmpty() ? 1 : (double) total / docs.size();
    }

    /** Her doküman için BM25 skoru (indeks sırasıyla). */
    public double[] scores(String query) {
        double[] s = new double[docs.size()];
        List<String> q = Tokenizer.tokens(query);
        int n = docs.size();
        for (String term : q) {
            Integer df = docFreq.get(term);
            if (df == null) continue;
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < n; i++) {
                Integer f = termFreqs.get(i).get(term);
                if (f == null) continue;
                double norm = f * (K1 + 1) / (f + K1 * (1 - B + B * lengths[i] / avgLength));
                s[i] += idf * norm;
            }
        }
        return s;
    }

    public List<KnowledgeChunk> docs() { return docs; }
}
