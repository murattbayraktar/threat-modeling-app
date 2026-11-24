package com.bank.tm.app.web;

import com.bank.tm.core.rag.RetrievedChunk;
import com.bank.tm.core.rag.Retriever;
import com.bank.tm.core.service.TmCore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bilgi tabanı: LLM'e gitmeden arama (RAG'in getirme katmanı). */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final TmCore core;

    public KnowledgeController(TmCore core) {
        this.core = core;
    }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", core.retriever.mode());
        m.put("counts", core.knowledgeBase.countsBySource());
        m.put("total", core.knowledgeBase.all().size());
        m.put("warnings", core.knowledgeBase.loadWarnings());
        return m;
    }

    @GetMapping("/search")
    public List<RetrievedChunk> search(@RequestParam("q") String q,
                                       @RequestParam(value = "sources", required = false) List<String> sources,
                                       @RequestParam(value = "tags", required = false) List<String> tags,
                                       @RequestParam(value = "k", defaultValue = "8") int k) {
        return core.retriever.search(new Retriever.Query(q, tags == null ? Set.of() : Set.copyOf(tags),
                sources == null || sources.isEmpty() ? null : Set.copyOf(sources), Math.min(Math.max(k, 1), 30), 0));
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        core.knowledgeBase.reload();
        core.retriever.rebuild();
        return sources();
    }
}
