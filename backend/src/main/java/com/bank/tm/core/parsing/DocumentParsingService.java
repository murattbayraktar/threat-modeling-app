package com.bank.tm.core.parsing;

import java.util.ArrayList;
import java.util.List;

/**
 * Dosyayı uygun ayrıştırıcıya yönlendirir. Tarayıcıda çıkarılmış metin (OCR / pdf.js) varsa ve sunucu tarafı
 * ayrıştırma yetersiz kalırsa (görsel, taranmış PDF) o metin kullanılır.
 */
public class DocumentParsingService {

    /** Sunucu ayrıştırmasından bu kadar karakterden az metin çıkarsa istemci metni tercih edilir. */
    private static final int MIN_USEFUL_CHARS = 80;

    private final List<DocumentParser> parsers;
    private final PlainTextParser textParser = new PlainTextParser();

    public DocumentParsingService(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public static DocumentParsingService withDefaults(List<DocumentParser> extra) {
        List<DocumentParser> all = new ArrayList<>(List.of(new DocxParser(), new DrawioParser(), new MarkdownParser(),
                new PlainTextParser()));
        all.addAll(extra);
        return new DocumentParsingService(all);
    }

    public ParsedDocument parse(String documentKey, String fileName, String contentType, byte[] content,
                                String clientText, String clientMethod) {
        List<String> warnings = new ArrayList<>();
        ParsedDocument server = null;
        DocumentParser parser = parsers.stream().filter(p -> p.supports(fileName, contentType)).findFirst().orElse(null);
        if (parser != null) {
            try {
                server = parser.parse(documentKey, fileName, content);
            } catch (Exception e) {
                warnings.add("Sunucu ayrıştırması başarısız (" + parser.name() + "): " + e.getMessage());
            }
        }
        boolean hasClient = clientText != null && !clientText.isBlank();
        if (server != null && (server.charCount() >= MIN_USEFUL_CHARS || !hasClient)) {
            if (server.charCount() < MIN_USEFUL_CHARS && server.diagram() == null) {
                warnings.add("Dokümandan çok az metin çıkarıldı. Taranmış bir doküman olabilir; tarayıcıda OCR ile yeniden yükleyin.");
            }
            return merge(server, warnings);
        }
        if (hasClient) {
            ParsedDocument client = textParser.parseText(documentKey, fileName, clientText,
                    "client:" + (clientMethod == null ? "unknown" : clientMethod));
            if (clientMethod != null && clientMethod.contains("ocr")) {
                warnings.add("Metin tarayıcıda OCR ile çıkarıldı; tanıma hataları olabilir.");
            }
            return merge(client, warnings);
        }
        String lower = fileName.toLowerCase();
        if (lower.matches(".*\\.(png|jpe?g|gif|bmp|webp|tiff?)$")) {
            warnings.add("Görsel dosya otomatik okunamadı (sunucuda görsel model/OCR yok). Tarayıcı OCR seçeneğini kullanın veya bileşenleri model ekranında elle ekleyin.");
        } else if (parser == null) {
            warnings.add("Desteklenmeyen dosya türü; içerik kullanılmadı.");
        }
        return new ParsedDocument(documentKey, fileName, "unknown", "none", List.of(), null, warnings);
    }

    private static ParsedDocument merge(ParsedDocument d, List<String> extra) {
        if (extra.isEmpty()) return d;
        List<String> all = new ArrayList<>(d.warnings());
        all.addAll(extra);
        return new ParsedDocument(d.documentKey(), d.fileName(), d.format(), d.extractionMethod(), d.sections(),
                d.diagram(), all);
    }
}
