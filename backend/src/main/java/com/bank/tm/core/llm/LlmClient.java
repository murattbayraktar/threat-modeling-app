package com.bank.tm.core.llm;

/** Sunucu tarafı LLM sağlayıcısı. Yeni bir sağlayıcı = bu arayüzün yeni uygulaması. */
public interface LlmClient {

    String name();

    String model();

    LlmResponse complete(LlmRequest request) throws Exception;
}
