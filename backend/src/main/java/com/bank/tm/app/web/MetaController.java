package com.bank.tm.app.web;

import com.bank.tm.app.config.TmProperties;
import com.bank.tm.core.domain.Stage;
import com.bank.tm.core.model.AuthMethod;
import com.bank.tm.core.model.ComponentType;
import com.bank.tm.core.model.DataClass;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.model.Zone;
import com.bank.tm.core.service.SampleLibrary;
import com.bank.tm.core.service.TmCore;
import com.bank.tm.core.util.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MetaController {

    private final TmCore core;
    private final TmProperties props;

    public MetaController(TmCore core, TmProperties props) {
        this.core = core;
        this.props = props;
    }

    /** Arayüzün ihtiyaç duyduğu sabitler ve ayarlar. */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stages", Arrays.stream(Stage.values()).map(s -> Map.of("id", s.name(), "label", s.label(),
                "actor", s.actor().name())).toList());
        m.put("componentTypes", names(ComponentType.values()));
        m.put("zones", names(Zone.values()));
        m.put("authMethods", names(AuthMethod.values()));
        m.put("dataClasses", names(DataClass.values()));
        m.put("stride", names(Stride.values()));
        m.put("riskLevels", core.rules.methodology().levels());
        Map<String, Object> llm = new LinkedHashMap<>();
        var sys = core.systemSettings.current();
        llm.put("serverProvider", core.llm.serverClient().name());
        llm.put("serverModel", core.llm.serverClient().model());
        llm.put("defaultMode", core.settings().defaultLlmMode());
        llm.put("browserEnabled", core.settings().browserLlmEnabled());
        llm.put("browserDefaultModel", sys.browserLlm().defaultModel());
        llm.put("browserConfigUrl", sys.browserLlm().configUrl());
        llm.put("browserModelBaseUrl", props.browserLlm() == null ? null : props.browserLlm().modelBaseUrl());
        m.put("llm", llm);
        // Tarayıcıda metin çıkarımı ve OCR ayarları (Sistem parametreleri ekranından yönetilir)
        var x = sys.extraction();
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("browserPdfText", x.browserPdfText());
        extraction.put("ocrMode", x.ocrMode());
        extraction.put("ocrLanguages", x.ocrLanguages());
        extraction.put("tesseractWorkerPath", x.tesseractWorkerPath());
        extraction.put("tesseractCorePath", x.tesseractCorePath());
        extraction.put("tesseractLangPath", x.tesseractLangPath());
        m.put("extraction", extraction);
        m.put("retrievalMode", core.retriever.mode());
        m.put("versions", Map.of("threatRules", core.rules.threatRulesVersion(), "riskRules",
                core.rules.riskRulesVersion(), "methodology", core.rules.methodology().version()));
        return m;
    }

    @GetMapping("/samples")
    public List<SampleLibrary.Sample> samples() {
        return core.samples.list();
    }

    /** Örnek proje dosyası (tarayıcıda OCR denemesi için). */
    @GetMapping(value = "/samples/{name}/files/{file}", produces = "application/octet-stream")
    public byte[] sampleFile(@PathVariable("name") String name, @PathVariable("file") String file) {
        return core.samples.read(name, file);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return core.metrics.compute();
    }

    /** Kural dosyaları (salt okunur görünüm) ve kullanılabilir operatörler. */
    @GetMapping("/rules")
    public Map<String, Object> rules() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        var dir = core.rules.rulesDir();
        m.put("threatRules", Json.tree(Files.readString(dir.resolve("threat-rules.json"))));
        m.put("riskRules", Json.tree(Files.readString(dir.resolve("risk-rules.json"))));
        m.put("methodology", Json.tree(Files.readString(dir.resolve("risk-methodology.json"))));
        m.put("operators", core.operators.names());
        m.put("loaded", Map.of("threatRules", core.rules.threatRules().size(), "riskRules", core.rules.riskRules().size()));
        return m;
    }

    /** Kural dosyaları değiştirildikten sonra yeniden yükler; hatalıysa önceki kurallar geçerli kalır. */
    @PostMapping("/rules/reload")
    public Map<String, Object> reload() throws IOException {
        core.rules.reload();
        return rules();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
