package com.bank.tm.core.llm;

/**
 * LLM kullanan akış adımları. DOCUMENT_VISION: doküman görselleri / diyagramların yorumlanması için ayrılmıştır;
 * görsel analiz adımı eklendiğinde bu adıma atanan model kullanılır (şimdilik akışta çağrılmaz).
 */
public enum LlmTask { MODEL_EXTRACTION, THREAT_GENERATION, DOCUMENT_VISION }
