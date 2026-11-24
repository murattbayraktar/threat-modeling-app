package com.bank.tm.core.parsing;

import java.util.List;

/**
 * Ayrıştırılmış doküman: bölüm kimlikli metin, varsa diyagram düğüm/kenarları ve uyarılar.
 * LLM'e yalnızca bu yapı (bölüm bölüm) gönderilir; ham dosya gönderilmez.
 */
public record ParsedDocument(
        String documentKey,
        String fileName,
        String format,
        String extractionMethod,
        List<Section> sections,
        Diagram diagram,
        List<String> warnings) {

    public ParsedDocument {
        sections = sections == null ? List.of() : List.copyOf(sections);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public int charCount() {
        return sections.stream().mapToInt(s -> s.text().length() + s.heading().length()).sum();
    }

    public record Section(String id, String heading, int level, String text) {
    }

    public record Diagram(List<Node> nodes, List<Edge> edges) {
        public Diagram {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    public record Node(String id, String label, String style) {
    }

    public record Edge(String id, String source, String target, String label) {
    }
}
