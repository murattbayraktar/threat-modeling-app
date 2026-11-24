package com.bank.tm.core.parsing;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Word (.docx) ayrıştırıcı. Ek kütüphane gerektirmez: word/document.xml doğrudan okunur.
 * Başlık stilleri (Heading/Başlık/Title) bölüm, liste paragrafları "- " satırı, tablolar "a | b | c" satırları olur.
 */
public class DocxParser implements DocumentParser {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Override
    public String name() { return "docx"; }

    @Override
    public boolean supports(String fileName, String contentType) {
        return fileName.toLowerCase().endsWith(".docx");
    }

    @Override
    public ParsedDocument parse(String documentKey, String fileName, byte[] content) throws Exception {
        byte[] xml = readEntry(content, "word/document.xml");
        if (xml == null) throw new IllegalArgumentException("Geçerli bir .docx dosyası değil: word/document.xml yok");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Element body = (Element) doc.getElementsByTagNameNS(W, "body").item(0);

        SectionBuilder b = new SectionBuilder();
        List<String> warnings = new ArrayList<>();
        int images = 0;
        NodeList children = body.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element el)) continue;
            if ("p".equals(el.getLocalName())) {
                images += el.getElementsByTagNameNS(W, "drawing").getLength();
                String text = paragraphText(el);
                if (text.isBlank()) continue;
                int headingLevel = headingLevel(el);
                if (headingLevel > 0) {
                    b.heading(text, headingLevel);
                } else if (isListItem(el)) {
                    b.line("- " + text);
                } else {
                    b.line(text);
                }
            } else if ("tbl".equals(el.getLocalName())) {
                NodeList rows = el.getElementsByTagNameNS(W, "tr");
                for (int r = 0; r < rows.getLength(); r++) {
                    NodeList cells = ((Element) rows.item(r)).getElementsByTagNameNS(W, "tc");
                    List<String> values = new ArrayList<>();
                    for (int c = 0; c < cells.getLength(); c++) values.add(paragraphText((Element) cells.item(c)));
                    b.line("| " + String.join(" | ", values) + " |");
                }
            }
        }
        if (images > 0) {
            warnings.add(images + " gömülü görsel bulundu. Görsel içerik otomatik okunmaz; diyagramın düzenlenebilir kaynağını (draw.io) yükleyin veya model ekranında doğrulayın.");
        }
        return new ParsedDocument(documentKey, fileName, "docx", "server:docx", b.build(), null, warnings);
    }

    private static String paragraphText(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList nodes = el.getElementsByTagNameNS(W, "*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            switch (n.getLocalName()) {
                case "t" -> sb.append(n.getTextContent());
                case "tab" -> sb.append(' ');
                case "br" -> sb.append(' ');
                default -> { }
            }
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    private static int headingLevel(Element p) {
        NodeList styles = p.getElementsByTagNameNS(W, "pStyle");
        if (styles.getLength() == 0) return 0;
        String val = ((Element) styles.item(0)).getAttributeNS(W, "val");
        String v = val.toLowerCase();
        if (v.equals("title") || v.equals("konubaşlığı")) return 1;
        if (v.startsWith("heading") || v.startsWith("başlık") || v.startsWith("baslik")) {
            String digits = v.replaceAll("\\D", "");
            return digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }
        return 0;
    }

    private static boolean isListItem(Element p) {
        if (p.getElementsByTagNameNS(W, "numPr").getLength() > 0) return true;
        NodeList styles = p.getElementsByTagNameNS(W, "pStyle");
        return styles.getLength() > 0
                && ((Element) styles.item(0)).getAttributeNS(W, "val").toLowerCase().startsWith("list");
    }

    static byte[] readEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zis.transferTo(out);
                    return out.toByteArray();
                }
            }
        }
        return null;
    }
}
