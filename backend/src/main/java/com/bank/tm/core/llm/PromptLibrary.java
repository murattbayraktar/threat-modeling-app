package com.bank.tm.core.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sürümlü prompt şablonları (resources/prompts/*.md). Biçim:
 * <pre>
 * ---
 * version: model-extraction.v1
 * ---
 * ## system
 * ...
 * ## user
 * ... {{placeholder}} ...
 * </pre>
 */
public class PromptLibrary {

    public record Prompt(String version, String system, String user) {
        public String renderUser(Map<String, String> values) {
            String out = user;
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
            }
            return out;
        }
    }

    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    public Prompt get(String name) {
        return cache.computeIfAbsent(name, PromptLibrary::load);
    }

    private static Prompt load(String name) {
        String path = "prompts/" + name + ".md";
        try (InputStream in = PromptLibrary.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Prompt bulunamadı: " + path);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            String version = name;
            if (text.startsWith("---")) {
                int end = text.indexOf("\n---", 3);
                for (String line : text.substring(3, end).split("\n")) {
                    if (line.startsWith("version:")) version = line.substring(8).strip();
                }
                text = text.substring(end + 4);
            }
            int sys = text.indexOf("## system");
            int usr = text.indexOf("## user");
            if (sys < 0 || usr < 0) throw new IllegalStateException(path + ": '## system' ve '## user' bölümleri gerekli");
            return new Prompt(version, text.substring(sys + 9, usr).strip(), text.substring(usr + 7).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
