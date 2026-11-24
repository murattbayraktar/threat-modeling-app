package com.bank.tm.core.parsing;

import java.util.ArrayList;
import java.util.List;

/** Başlık/satır akışından bölüm listesi üretir. Bölüm kimliği numaralı başlıktan veya sıradan türetilir. */
final class SectionBuilder {

    private final List<ParsedDocument.Section> sections = new ArrayList<>();
    private String heading = "Giriş";
    private int level = 1;
    private String id = "0";
    private final StringBuilder text = new StringBuilder();
    private int counter = 0;

    void heading(String rawHeading, int headingLevel) {
        flush();
        counter++;
        String h = rawHeading.strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+").matcher(h);
        id = m.find() ? m.group(1) : "S" + counter;
        heading = h;
        level = headingLevel;
    }

    void line(String line) {
        if (line == null) return;
        String l = line.stripTrailing();
        if (l.isBlank()) return;
        if (!text.isEmpty()) text.append('\n');
        text.append(l);
    }

    List<ParsedDocument.Section> build() {
        flush();
        return sections;
    }

    private void flush() {
        if (!text.isEmpty()) {
            String uniqueId = id;
            for (ParsedDocument.Section s : sections) {
                if (s.id().equals(uniqueId)) uniqueId = id + "-" + counter;
            }
            sections.add(new ParsedDocument.Section(uniqueId, heading, level, text.toString()));
        }
        text.setLength(0);
    }
}
