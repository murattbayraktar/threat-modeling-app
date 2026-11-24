package com.bank.tm.core.llm;

import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** contracts/ altındaki şemalar (Maven derlemesinde classpath'e kopyalanır). */
public final class SchemaRegistry {

    public static final String MODEL_EXTRACTION = "contracts/llm/model-extraction.output.schema.json";
    public static final String THREAT_GENERATION = "contracts/llm/threat-generation.output.schema.json";
    public static final String SYSTEM_MODEL = "contracts/system-model.schema.json";

    private SchemaRegistry() {
    }

    public static JsonNode load(String path) {
        try (InputStream in = SchemaRegistry.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Şema bulunamadı (classpath): " + path);
            return Json.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sağlayıcıya gönderilecek sade şema ($schema/$id/title kaldırılır; bazı sağlayıcılar reddeder). */
    public static JsonNode forProvider(JsonNode schema) {
        ObjectNode copy = schema.deepCopy();
        copy.remove("$schema");
        copy.remove("$id");
        copy.remove("title");
        return copy;
    }
}
