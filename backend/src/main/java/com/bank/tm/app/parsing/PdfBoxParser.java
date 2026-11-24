package com.bank.tm.app.parsing;

import com.bank.tm.core.parsing.DocumentParser;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.parsing.PlainTextParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Metin içeren PDF'ler için sunucu tarafı ayrıştırıcı (PDFBox). Taranmış PDF'lerde metin çıkmaz; bu durumda
 * tarayıcıda (pdf.js + tesseract.js) çıkarılan metin kullanılır.
 */
public class PdfBoxParser implements DocumentParser {

    private final PlainTextParser text = new PlainTextParser();

    @Override
    public String name() { return "pdfbox"; }

    @Override
    public boolean supports(String fileName, String contentType) {
        return fileName.toLowerCase().endsWith(".pdf") || "application/pdf".equals(contentType);
    }

    @Override
    public ParsedDocument parse(String documentKey, String fileName, byte[] content) throws Exception {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String extracted = stripper.getText(doc);
            ParsedDocument p = text.parseText(documentKey, fileName, extracted, "server:pdfbox");
            return new ParsedDocument(p.documentKey(), fileName, "pdf", p.extractionMethod(), p.sections(), null,
                    p.warnings());
        }
    }
}
