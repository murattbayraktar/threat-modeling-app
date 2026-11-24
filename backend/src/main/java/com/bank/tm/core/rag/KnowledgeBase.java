package com.bank.tm.core.rag;

import com.bank.tm.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * datasets/knowledge-base altındaki *.jsonl dosyalarını yükler. Her satır bir {@link KnowledgeChunk}.
 * Yeni kaynak eklemek için aynı formatta yeni bir .jsonl dosyası bırakıp reload çağırmak yeterlidir.
 */
public class KnowledgeBase {

    private final Path dir;
    private volatile List<KnowledgeChunk> chunks = List.of();
    private volatile Map<String, KnowledgeChunk> byId = Map.of();
    private volatile List<String> loadWarnings = List.of();

    public KnowledgeBase(Path dir) {
        this.dir = dir;
        reload();
    }

    public synchronized void reload() {
        List<KnowledgeChunk> all = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                int lineNo = 0;
                for (String line : Files.readAllLines(f)) {
                    lineNo++;
                    if (line.isBlank()) continue;
                    try {
                        KnowledgeChunk c = Json.read(line, KnowledgeChunk.class);
                        if (c.id() == null || !ids.add(c.id())) {
                            warnings.add(f.getFileName() + ":" + lineNo + " id eksik veya tekrar ediyor");
                            continue;
                        }
                        all.add(c);
                    } catch (RuntimeException e) {
                        warnings.add(f.getFileName() + ":" + lineNo + " okunamadı: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Bilgi tabanı okunamadı: " + dir, e);
        }
        Map<String, KnowledgeChunk> map = new LinkedHashMap<>();
        all.forEach(c -> map.put(c.id(), c));
        chunks = List.copyOf(all);
        byId = map;
        loadWarnings = List.copyOf(warnings);
    }

    public List<KnowledgeChunk> all() { return chunks; }

    public Optional<KnowledgeChunk> get(String id) { return Optional.ofNullable(byId.get(id)); }

    public List<String> loadWarnings() { return loadWarnings; }

    public Map<String, Long> countsBySource() {
        Map<String, Long> m = new LinkedHashMap<>();
        chunks.forEach(c -> m.merge(c.source(), 1L, Long::sum));
        return m;
    }
}
