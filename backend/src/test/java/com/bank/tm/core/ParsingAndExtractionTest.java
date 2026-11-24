package com.bank.tm.core;

import com.bank.tm.core.llm.HeuristicModelExtractor;
import com.bank.tm.core.model.AuthMethod;
import com.bank.tm.core.model.Component;
import com.bank.tm.core.model.ComponentType;
import com.bank.tm.core.model.DataFlow;
import com.bank.tm.core.model.ModelExtractionOutput;
import com.bank.tm.core.model.Zone;
import com.bank.tm.core.parsing.DocumentParsingService;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.llm.SchemaRegistry;
import com.bank.tm.core.util.Json;
import com.bank.tm.core.validation.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsingAndExtractionTest {

    private final DocumentParsingService parsing = DocumentParsingService.withDefaults(List.of());

    private ParsedDocument parse(String sample, String file, String key) throws Exception {
        Path p = Fixtures.datasets().resolve("sample-projects").resolve(sample).resolve(file);
        return parsing.parse(key, file, null, Files.readAllBytes(p), null, null);
    }

    @Test
    void docxSectionsListsAndTables() throws Exception {
        ParsedDocument d = parse("tedarikci-fatura-entegrasyonu", "Mimari_Tasarim.docx", "D1");
        assertEquals("server:docx", d.extractionMethod());
        ParsedDocument.Section comps = d.sections().stream().filter(s -> s.heading().contains("Bileşenler")).findFirst().orElseThrow();
        assertEquals("2", comps.id());
        assertTrue(comps.text().contains("- API Gateway: DMZ"));
        assertTrue(d.sections().stream().anyMatch(s -> s.text().contains("| Fatura Veritabanı | Oracle 19c |")));
    }

    @Test
    void drawioNodesAndEdges() throws Exception {
        ParsedDocument d = parse("tedarikci-fatura-entegrasyonu", "Entegrasyon_Akisi.drawio", "D2");
        assertNotNull(d.diagram());
        assertEquals(5, d.diagram().nodes().size());
        assertEquals(4, d.diagram().edges().size());
    }

    @Test
    void imageWithoutClientTextWarns() throws Exception {
        ParsedDocument d = parse("mobil-sube-randevu", "sistem_semasi.png", "D2");
        assertTrue(d.sections().isEmpty());
        assertFalse(d.warnings().isEmpty());
        ParsedDocument withOcr = parsing.parse("D2", "sistem_semasi.png", "image/png", new byte[0],
                "Bilesenler:\n- SMS Servisi: ucuncu taraf", "browser-ocr");
        assertTrue(withOcr.extractionMethod().startsWith("client:"));
        assertEquals(1, withOcr.sections().size());
    }

    @Test
    void heuristicExtractionOfInvoiceSample() throws Exception {
        List<ParsedDocument> docs = List.of(parse("tedarikci-fatura-entegrasyonu", "Mimari_Tasarim.docx", "D1"),
                parse("tedarikci-fatura-entegrasyonu", "Entegrasyon_Akisi.drawio", "D2"));
        ModelExtractionOutput out = HeuristicModelExtractor.extract(docs, "Fatura");
        Component gw = out.components().stream().filter(c -> c.name().equals("API Gateway")).findFirst().orElseThrow();
        assertEquals(ComponentType.API_GATEWAY, gw.type());
        assertEquals(Zone.DMZ, gw.zone());
        assertEquals(Boolean.TRUE, gw.internetFacing());
        assertEquals(ComponentType.FILE_STORAGE, byName(out, "Dosya Aktarım Sunucusu").type());
        assertEquals(ComponentType.CORE_BANKING, byName(out, "Muhasebe Sistemi").type());
        assertEquals(10, out.components().size(), "9 doküman bileşeni + 1 yalnızca diyagramda olan");
        DataFlow f = out.dataFlows().stream().filter(x -> x.source().equals(gw.id())
                && x.target().equals(byName(out, "Fatura Entegrasyon Servisi").id())).findFirst().orElseThrow();
        assertEquals(AuthMethod.UNKNOWN, f.authentication());
        assertEquals("REST", f.protocol());
        // LLM şemasına uygun olmalı (mock çıktısı da gerçek sağlayıcıyla aynı kontrattan geçer)
        List<String> errors = new JsonSchemaValidator(SchemaRegistry.load(SchemaRegistry.MODEL_EXTRACTION))
                .validate(Json.toTree(out));
        assertTrue(errors.isEmpty(), "Şema hataları: " + errors);
    }

    @Test
    void schemaValidatorReportsProblems() {
        List<String> errors = new JsonSchemaValidator(SchemaRegistry.load(SchemaRegistry.THREAT_GENERATION))
                .validate(Json.tree("{\"threats\":[{\"candidateId\":null,\"strideCategory\":\"XSS\",\"extra\":1}]}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("strideCategory")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("tanımsız alan 'extra'")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("zorunlu alan eksik 'title'")));
    }

    private static Component byName(ModelExtractionOutput o, String name) {
        return o.components().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(
                () -> new AssertionError(name + " yok: " + o.components().stream().map(Component::name).toList()));
    }
}
