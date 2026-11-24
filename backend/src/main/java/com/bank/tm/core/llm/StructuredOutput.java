package com.bank.tm.core.llm;

import com.bank.tm.core.util.Json;
import com.bank.tm.core.validation.JsonSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** LLM metin çıktısını JSON'a çevirir ve şemaya göre doğrular. */
public final class StructuredOutput {

    public record Result(JsonNode json, List<String> errors) {
        public boolean valid() { return errors.isEmpty(); }
    }

    private StructuredOutput() {
    }

    public static Result parse(String raw, JsonNode schema) {
        if (raw == null || raw.isBlank()) return new Result(null, List.of("Boş yanıt"));
        String text = stripFences(raw.strip());
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(text);
        } catch (Exception e) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) return new Result(null, List.of("Geçerli JSON değil: " + e.getMessage()));
            try {
                node = Json.MAPPER.readTree(text.substring(start, end + 1));
            } catch (Exception e2) {
                return new Result(null, List.of("Geçerli JSON değil: " + e2.getMessage()));
            }
        }
        List<String> errors = new JsonSchemaValidator(schema).validate(node);
        return new Result(node, errors);
    }

    static String stripFences(String s) {
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) return s.substring(nl + 1, end).strip();
        }
        return s;
    }
}
