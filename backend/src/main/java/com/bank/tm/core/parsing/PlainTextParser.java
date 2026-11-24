package com.bank.tm.core.parsing;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Düz metin. Başlık sezgisi: "2. BİLEŞENLER", "2.1 Akışlar" gibi numaralı satırlar veya tamamı büyük harf kısa satırlar.
 * Tarayıcıda OCR/pdf.js ile çıkarılan metin de bu ayrıştırıcıdan geçer.
 */
public class PlainTextParser implements DocumentParser {

    private static final Pattern NUMBERED = Pattern.compile("^\\d+(\\.\\d+)*\\.?\\s+\\S.{0,80}$");

    @Override
    public String name() { return "text"; }

    @Override
    public boolean supports(String fileName, String contentType) {
        String f = fileName.toLowerCase();
        return f.endsWith(".txt") || f.endsWith(".text") || (contentType != null && contentType.startsWith("text/plain"));
    }

    @Override
    public ParsedDocument parse(String documentKey, String fileName, byte[] content) {
        return parseText(documentKey, fileName, new String(content, StandardCharsets.UTF_8), "server:text");
    }

    public ParsedDocument parseText(String documentKey, String fileName, String text, String method) {
        SectionBuilder b = new SectionBuilder();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (isHeading(t)) {
                b.heading(t.endsWith(":") ? t.substring(0, t.length() - 1) : t, 1);
            } else {
                b.line(t.startsWith("• ") || t.startsWith("* ") ? "- " + t.substring(2) : t);
            }
        }
        return new ParsedDocument(documentKey, fileName, "text", method, b.build(), null, List.of());
    }

    static boolean isHeading(String t) {
        if (t.startsWith("-") || t.contains("→") || t.contains("->")) return false;
        if (NUMBERED.matcher(t).matches() && t.length() < 80 && !t.endsWith(".")) return true;
        String letters = t.replaceAll("[^\\p{L}]", "");
        boolean upper = letters.length() >= 4 && letters.equals(letters.toUpperCase(java.util.Locale.forLanguageTag("tr")));
        return (upper && t.length() <= 60) || (t.endsWith(":") && t.length() <= 40 && !t.contains(" - "));
    }
}
