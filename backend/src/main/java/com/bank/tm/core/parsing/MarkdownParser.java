package com.bank.tm.core.parsing;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class MarkdownParser implements DocumentParser {

    @Override
    public String name() { return "markdown"; }

    @Override
    public boolean supports(String fileName, String contentType) {
        String f = fileName.toLowerCase();
        return f.endsWith(".md") || f.endsWith(".markdown");
    }

    @Override
    public ParsedDocument parse(String documentKey, String fileName, byte[] content) {
        SectionBuilder b = new SectionBuilder();
        for (String line : new String(content, StandardCharsets.UTF_8).split("\\R")) {
            String t = line.strip();
            if (t.startsWith("#")) {
                int lvl = 0;
                while (lvl < t.length() && t.charAt(lvl) == '#') lvl++;
                b.heading(t.substring(lvl).strip(), Math.max(1, lvl - 1));
            } else if (t.startsWith("* ") || t.startsWith("+ ")) {
                b.line("- " + t.substring(2));
            } else {
                b.line(t);
            }
        }
        return new ParsedDocument(documentKey, fileName, "markdown", "server:markdown", b.build(), null, List.of());
    }
}
