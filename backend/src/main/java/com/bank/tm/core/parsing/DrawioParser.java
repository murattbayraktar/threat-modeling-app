package com.bank.tm.core.parsing;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * draw.io / diagrams.net (.drawio, .xml) ayrıştırıcı. Düğümler (vertex) ve bağlantılar (edge) doğrudan okunur;
 * sıkıştırılmış diyagram içerikleri (deflate + base64) de desteklenir. OCR gerektirmez.
 */
public class DrawioParser implements DocumentParser {

    @Override
    public String name() { return "drawio"; }

    @Override
    public boolean supports(String fileName, String contentType) {
        String f = fileName.toLowerCase();
        return f.endsWith(".drawio") || f.endsWith(".drawio.xml") || f.endsWith(".dio");
    }

    @Override
    public ParsedDocument parse(String documentKey, String fileName, byte[] content) throws Exception {
        Document doc = parseXml(content);
        NodeList diagrams = doc.getElementsByTagName("diagram");
        List<ParsedDocument.Node> nodes = new ArrayList<>();
        List<ParsedDocument.Edge> edges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<Document> models = new ArrayList<>();
        if (doc.getElementsByTagName("mxGraphModel").getLength() > 0) models.add(doc);
        for (int i = 0; i < diagrams.getLength(); i++) {
            Element d = (Element) diagrams.item(i);
            if (d.getElementsByTagName("mxGraphModel").getLength() == 0 && !d.getTextContent().isBlank()) {
                models.add(parseXml(inflate(d.getTextContent().strip())));
            }
        }
        for (Document m : models) {
            NodeList cells = m.getElementsByTagName("mxCell");
            Map<String, String> labels = new HashMap<>();
            for (int i = 0; i < cells.getLength(); i++) {
                Element c = (Element) cells.item(i);
                if ("1".equals(c.getAttribute("vertex"))) {
                    String label = clean(c.getAttribute("value"));
                    if (label.isBlank()) continue;
                    labels.put(c.getAttribute("id"), label);
                    nodes.add(new ParsedDocument.Node(c.getAttribute("id"), label, c.getAttribute("style")));
                }
            }
            for (int i = 0; i < cells.getLength(); i++) {
                Element c = (Element) cells.item(i);
                if ("1".equals(c.getAttribute("edge"))) {
                    String s = c.getAttribute("source");
                    String t = c.getAttribute("target");
                    if (labels.containsKey(s) && labels.containsKey(t)) {
                        edges.add(new ParsedDocument.Edge(c.getAttribute("id"), s, t, clean(c.getAttribute("value"))));
                    } else {
                        warnings.add("Bağlantısı eksik bir ok atlandı (id=" + c.getAttribute("id") + ")");
                    }
                }
            }
        }

        // Diyagramı LLM'in okuyabileceği bölümlere de çevir.
        Map<String, String> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n.label()));
        StringBuilder comp = new StringBuilder();
        nodes.forEach(n -> comp.append("- ").append(n.label()).append('\n'));
        StringBuilder flow = new StringBuilder();
        edges.forEach(e -> flow.append("- ").append(byId.get(e.source())).append(" → ").append(byId.get(e.target()))
                .append(e.label().isBlank() ? "" : ": " + e.label()).append('\n'));
        List<ParsedDocument.Section> sections = new ArrayList<>();
        if (!comp.isEmpty()) sections.add(new ParsedDocument.Section("diagram-nodes", "Diyagram bileşenleri", 1, comp.toString().strip()));
        if (!flow.isEmpty()) sections.add(new ParsedDocument.Section("diagram-edges", "Diyagram veri akışları", 1, flow.toString().strip()));
        return new ParsedDocument(documentKey, fileName, "drawio", "server:drawio", sections,
                new ParsedDocument.Diagram(nodes, edges), warnings);
    }

    private static String clean(String html) {
        return html.replaceAll("<br\\s*/?>", " ").replaceAll("<[^>]+>", "").replace("&nbsp;", " ")
                .replace("&amp;", "&").replaceAll("\\s+", " ").strip();
    }

    private static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static byte[] inflate(String base64) throws Exception {
        byte[] compressed = Base64.getDecoder().decode(base64);
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!inflater.finished()) {
            int n = inflater.inflate(buf);
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
            out.write(buf, 0, n);
        }
        return URLDecoder.decode(out.toString(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8);
    }
}
