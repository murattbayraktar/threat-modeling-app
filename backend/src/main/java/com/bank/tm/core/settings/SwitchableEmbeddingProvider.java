package com.bank.tm.core.settings;

import com.bank.tm.core.rag.EmbeddingProvider;

/** Çalışırken açılıp kapatılabilen embedding sağlayıcısı; kapalıyken RAG yalnızca BM25 ile çalışır. */
public final class SwitchableEmbeddingProvider implements EmbeddingProvider {

    private volatile EmbeddingProvider delegate;

    public SwitchableEmbeddingProvider(EmbeddingProvider initial) {
        this.delegate = initial;
    }

    public void replace(EmbeddingProvider next) {
        this.delegate = next;
    }

    @Override
    public boolean available() {
        return delegate != null;
    }

    @Override
    public String name() {
        EmbeddingProvider d = delegate;
        return d == null ? "none" : d.name();
    }

    @Override
    public float[] embed(String text) throws Exception {
        EmbeddingProvider d = delegate;
        if (d == null) throw new IllegalStateException("Embedding kapalı");
        return d.embed(text);
    }
}
