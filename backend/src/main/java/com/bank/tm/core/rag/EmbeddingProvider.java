package com.bank.tm.core.rag;

/**
 * İsteğe bağlı vektör katmanı. Kurum içi gen AI ortamı bir embedding uç noktası sunuyorsa
 * {@link OpenAiCompatibleEmbeddingProvider} etkinleştirilerek hibrit arama (BM25 + vektör) yapılır.
 */
public interface EmbeddingProvider {

    String name();

    /** Kapatılabilen sağlayıcılar için; false ise yalnızca BM25 kullanılır. */
    default boolean available() {
        return true;
    }

    float[] embed(String text) throws Exception;
}
