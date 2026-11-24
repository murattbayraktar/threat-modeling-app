package com.bank.tm.core.llm;

import com.bank.tm.core.model.AuthMethod;
import com.bank.tm.core.model.Component;
import com.bank.tm.core.model.ComponentType;
import com.bank.tm.core.model.Confidence;
import com.bank.tm.core.model.DataClass;
import com.bank.tm.core.model.DataFlow;
import com.bank.tm.core.model.Evidence;
import com.bank.tm.core.model.MissingInformation;
import com.bank.tm.core.model.ModelExtractionOutput;
import com.bank.tm.core.model.SystemInfo;
import com.bank.tm.core.model.Zone;
import com.bank.tm.core.parsing.ParsedDocument;
import com.bank.tm.core.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anahtar kelime tabanlı, deterministik model çıkarıcı. Mock LLM sağlayıcısı tarafından kullanılır: gerçek bir
 * modele erişim olmadan uçtan uca akışı denemek ve testleri çalıştırmak içindir. Gerçek LLM'in yerine geçmez;
 * "Bileşenler" / "Veri akışları" başlıklı listeler ve draw.io diyagramları üzerinde iyi çalışır.
 */
public class HeuristicModelExtractor {

    private record Rule<T>(Pattern pattern, T value) {
    }

    private static <T> Rule<T> r(String regex, T value) {
        return new Rule<>(Pattern.compile(regex), value);
    }

    private static final List<Rule<ComponentType>> TYPE_RULES = List.of(
            r("api gateway|\\bgateway\\b", ComponentType.API_GATEWAY),
            r("veritaban|database|\\boracle\\b|postgres|mysql|mssql|\\bdb\\b", ComponentType.DATABASE),
            r("kuyrug|kuyruk|queue|kafka|rabbit|\\bmq\\b", ComponentType.MESSAGE_QUEUE),
            r("dosya deposu|belge deposu|depolama|storage|dosya sunucu|dosya aktarim|\\bsftp\\b", ComponentType.FILE_STORAGE),
            r("cekirdek bankacilik|core banking|muhasebe", ComponentType.CORE_BANKING),
            r("kimlik saglayici|identity provider|\\bidp\\b|keycloak|active directory", ComponentType.IDENTITY_PROVIDER),
            r("\\berp\\b|ucuncu taraf|third party|tedarikci sistem|saglayici", ComponentType.THIRD_PARTY_SYSTEM),
            r("mobil uygulama|mobile app|\\bios\\b|android", ComponentType.MOBILE_APP),
            r("portal|web uygulama|web arayuz|frontend|web app", ComponentType.WEB_APP),
            r("\\bapi\\b|\\brest\\b|endpoint|uc nokta", ComponentType.API),
            r("toplu is|batch|gece calisan|zamanlanmis", ComponentType.BATCH_JOB),
            r("\\bkullanici\\b|\\bmusteri\\b|\\buser\\b", ComponentType.USER),
            r("servis|service|uygulama|application|is akisi|workflow|sistem", ComponentType.SERVICE));

    private static final List<Rule<Zone>> ZONE_RULES = List.of(
            r("\\bdmz\\b", Zone.DMZ),
            r("ic ag|\\bic (servis|uygulama|sistem)|internal|banka ici|kurum ici", Zone.INTERNAL),
            r("ucuncu taraf|third party", Zone.THIRD_PARTY),
            r("internet|disaridan", Zone.INTERNET));

    private static final List<Rule<AuthMethod>> AUTH_RULES = List.of(
            r("\\bmtls\\b|mutual tls|karsilikli tls", AuthMethod.MTLS),
            r("oauth", AuthMethod.OAUTH2),
            r("\\bjwt\\b", AuthMethod.JWT),
            r("\\bsso\\b|ldap|active directory", AuthMethod.SSO),
            r("api anahtar|api key", AuthMethod.API_KEY),
            r("servis hesab|service account", AuthMethod.SERVICE_ACCOUNT),
            r("basic auth|parola ile giris|sifre ile giris|e-posta ve parola", AuthMethod.BASIC),
            r("kimlik dogrulama yok|anonim", AuthMethod.NONE));

    private static final List<Rule<DataClass>> DATA_RULES = List.of(
            r("musteri|customer", DataClass.CUSTOMER),
            r("kisisel|tckn|kimlik no|telefon|ad soyad|musteri adi|\\bpii\\b", DataClass.PII),
            r("iban|hesap|fatura|odeme|tutar|finansal|bakiye|talimat|vergi", DataClass.FINANCIAL),
            r("\\bkart\\b|kart numara|\\bpan\\b|\\bcard\\b", DataClass.CARD),
            r("(?<!client )credential|kimlik bilgileri saklan|parola(lar)? (tutul|saklan)", DataClass.CREDENTIAL));

    private static final List<Rule<String>> PROTOCOL_RULES = List.of(
            r("\\bsftp\\b", "SFTP"), r("\\bhttps\\b", "HTTPS"), r("\\bhttp\\b", "HTTP"), r("\\bsoap\\b", "SOAP"),
            r("\\brest\\b", "REST"), r("\\bjdbc\\b", "JDBC"), r("\\bsql\\b", "SQL"), r("\\bgrpc\\b", "gRPC"),
            r("\\bamqp\\b", "AMQP"), r("kafka", "Kafka"), r("\\bftp\\b", "FTP"));

    private static final Pattern ENCRYPTED_TRUE = Pattern.compile("\\bhttps\\b|\\btls\\b|\\bmtls\\b|\\bsftp\\b|\\bssl\\b|sifreli");
    private static final Pattern ENCRYPTED_FALSE = Pattern.compile("\\bhttp\\b|\\bftp\\b|sifresiz|duz metin");
    private static final Pattern TECH = Pattern.compile("(spring boot|oracle(?: \\d+\\w*)?|postgresql|kafka|rabbitmq|ibm mq|java \\d+|\\.net|node\\.js|sftp)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN = Pattern.compile("\\(([^)]*)\\)");
    private static final Pattern ARROW = Pattern.compile("\\s*(?:→|->|=>)\\s*");

    private static final class Draft {
        String id;
        String name;
        String text;
        Evidence evidence;
        String technology;
    }

    private final Map<String, Draft> components = new LinkedHashMap<>();
    private final List<DataFlow> flows = new ArrayList<>();
    private final Set<String> assumptions = new LinkedHashSet<>();

    public static ModelExtractionOutput extract(List<ParsedDocument> docs, String systemName) {
        return new HeuristicModelExtractor().run(docs, systemName);
    }

    private ModelExtractionOutput run(List<ParsedDocument> docs, String systemName) {
        String description = "";
        // 1) Bileşenler
        for (ParsedDocument d : docs) {
            for (ParsedDocument.Section s : d.sections()) {
                String h = Text.fold(s.heading());
                if (description.isEmpty() && (h.contains("amac") || h.contains("genel") || h.contains("kapsam"))) {
                    description = Text.truncate(Text.squash(s.text()), 400);
                }
                if (isComponentSection(s)) {
                    for (String line : bulletLines(s.text())) addComponent(line, new Evidence(d.documentKey(), s.id(), line));
                }
            }
        }
        // 2) Akışlar
        for (ParsedDocument d : docs) {
            for (ParsedDocument.Section s : d.sections()) {
                for (String line : bulletLines(s.text())) {
                    if (ARROW.matcher(line).find()) addFlow(line, new Evidence(d.documentKey(), s.id(), line));
                }
            }
        }
        List<Component> comps = inferInternetFacing(components.values().stream().map(this::toComponent).toList());
        return new ModelExtractionOutput(new SystemInfo(systemName == null ? "Sistem" : systemName, description),
                comps, flows, missing(comps), new ArrayList<>(assumptions));
    }

    /** Bölgesi bilinmeyen ama internetteki bir bileşen tarafından çağrılan süreçler internete açık varsayılır. */
    private List<Component> inferInternetFacing(List<Component> comps) {
        Map<String, Component> byId = new LinkedHashMap<>();
        comps.forEach(c -> byId.put(c.id(), c));
        List<Component> out = new ArrayList<>();
        for (Component c : comps) {
            boolean calledFromInternet = flows.stream().anyMatch(f -> f.target().equals(c.id())
                    && byId.containsKey(f.source()) && byId.get(f.source()).zone() == Zone.INTERNET);
            if (c.internetFacing() == null && c.zone() == Zone.UNKNOWN
                    && c.type().elementKind() == com.bank.tm.core.model.ElementKind.PROCESS && calledFromInternet) {
                assumptions.add(c.name() + " internetteki bir bileşen tarafından çağrıldığı için internete açık varsayıldı.");
                out.add(new Component(c.id(), c.name(), c.type(), c.technology(), c.zone(), Boolean.TRUE,
                        c.authentication(), c.dataClasses(), c.description(), c.evidence(), Confidence.LOW, null));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean isComponentSection(ParsedDocument.Section s) {
        String h = Text.fold(s.heading());
        if (s.id().equals("diagram-nodes")) return true;
        return (h.contains("bilesen") || h.contains("component") || h.contains("sistemler"))
                && !h.contains("akis") && !h.contains("flow");
    }

    private static List<String> bulletLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.startsWith("- ")) out.add(t.substring(2).strip());
        }
        return out;
    }

    private void addComponent(String line, Evidence ev) {
        if (ARROW.matcher(line).find()) return;
        String name = line;
        String desc = "";
        int colon = line.indexOf(':');
        if (colon > 0 && colon < 80) {
            name = line.substring(0, colon).strip();
            desc = line.substring(colon + 1).strip();
        }
        String tech = null;
        Matcher pm = PAREN.matcher(name);
        if (pm.find()) {
            tech = pm.group(1).strip();
            name = PAREN.matcher(name).replaceAll("").strip();
        }
        if (name.isBlank() || name.length() > 80) return;
        String key = Text.fold(name);
        Draft existing = components.get(key);
        if (existing != null) {
            if (!desc.isBlank() && !existing.text.contains(desc)) existing.text += " " + desc;
            if (existing.technology == null) existing.technology = tech;
            return;
        }
        Draft d = new Draft();
        d.id = "C" + (components.size() + 1);
        d.name = name;
        d.text = name + ": " + desc;
        d.evidence = ev;
        d.technology = tech;
        components.put(key, d);
    }

    private void addFlow(String line, Evidence ev) {
        String head = line;
        String desc = "";
        int colon = line.indexOf(':', line.indexOf('→') >= 0 ? line.indexOf('→') : 0);
        if (colon > 0) {
            head = line.substring(0, colon);
            desc = line.substring(colon + 1).strip();
        }
        String[] parts = ARROW.split(head, 2);
        if (parts.length != 2) return;
        Draft src = resolve(parts[0], ev);
        Draft tgt = resolve(parts[1], ev);
        if (src == null || tgt == null || src == tgt) return;
        String t = Text.fold(desc + " " + head);
        String protocol = first(PROTOCOL_RULES, t);
        AuthMethod auth = first(AUTH_RULES, Text.fold(desc));
        Boolean encrypted = ENCRYPTED_TRUE.matcher(t).find() ? Boolean.TRUE
                : ENCRYPTED_FALSE.matcher(t).find() ? Boolean.FALSE : null;
        List<DataClass> data = all(DATA_RULES, Text.fold(desc));
        // Aynı yön için tekrar eden akış: eksik alanları tamamla
        for (int i = 0; i < flows.size(); i++) {
            DataFlow f = flows.get(i);
            if (f.source().equals(src.id) && f.target().equals(tgt.id)) {
                Set<DataClass> merged = new LinkedHashSet<>(f.dataClasses());
                merged.addAll(data);
                flows.set(i, new DataFlow(f.id(), f.source(), f.target(),
                        f.description().isBlank() ? desc : f.description(),
                        f.protocol() != null ? f.protocol() : protocol,
                        f.authentication() != AuthMethod.UNKNOWN ? f.authentication() : orUnknown(auth),
                        f.encrypted() != null ? f.encrypted() : encrypted, new ArrayList<>(merged), f.evidence(),
                        f.confidence(), null));
                return;
            }
        }
        flows.add(new DataFlow("F" + (flows.size() + 1), src.id, tgt.id, desc, protocol, orUnknown(auth), encrypted,
                data, ev, protocol == null && auth == null ? Confidence.LOW : Confidence.MEDIUM, null));
    }

    private Draft resolve(String endpoint, Evidence ev) {
        String clean = PAREN.matcher(endpoint).replaceAll("").strip();
        String key = Text.fold(clean);
        if (components.containsKey(key)) return components.get(key);
        Draft best = null;
        for (Draft d : components.values()) {
            String n = Text.fold(d.name);
            if ((key.contains(n) || n.contains(key)) && (best == null || n.length() > Text.fold(best.name).length())) {
                best = d;
            }
        }
        if (best != null) return best;
        addComponent(clean, ev);
        Draft created = components.get(key);
        if (created != null) assumptions.add("'" + clean + "' yalnızca bir akışta geçiyor; bileşen olarak eklendi.");
        return created;
    }

    private Component toComponent(Draft d) {
        String t = Text.fold(d.text);
        // Önce adın kendisi (genel kurallar hariç), sonra açıklama dahil tüm metin.
        ComponentType type = first(TYPE_RULES.subList(0, TYPE_RULES.size() - 2), Text.fold(d.name));
        if (type == null) type = first(TYPE_RULES, t);
        if (type == null) type = ComponentType.OTHER;
        Zone zone = first(ZONE_RULES, t);
        if (zone == null) {
            zone = switch (type) {
                case USER, MOBILE_APP -> Zone.INTERNET;
                case THIRD_PARTY_SYSTEM, EXTERNAL_SYSTEM -> Zone.THIRD_PARTY;
                case API_GATEWAY -> Zone.DMZ;
                case DATABASE, CORE_BANKING, MESSAGE_QUEUE, FILE_STORAGE -> Zone.INTERNAL;
                default -> Zone.UNKNOWN;
            };
            if (zone != Zone.UNKNOWN) {
                assumptions.add(d.name + " için ağ bölgesi belirtilmedi; bileşen türüne göre " + zone + " varsayıldı.");
            }
        }
        Boolean internetFacing = switch (zone) {
            case INTERNET -> type.elementKind() == com.bank.tm.core.model.ElementKind.PROCESS ? Boolean.TRUE : null;
            case DMZ -> type == ComponentType.API_GATEWAY && Pattern.compile("dis istek|internet|disaridan").matcher(t).find()
                    ? Boolean.TRUE : null;
            case INTERNAL, THIRD_PARTY -> Boolean.FALSE;
            default -> null;
        };
        if (t.contains("internete acik")) internetFacing = Boolean.TRUE;
        AuthMethod auth = first(AUTH_RULES, t);
        String tech = d.technology;
        Matcher tm = TECH.matcher(d.text);
        if (tech == null && tm.find()) tech = tm.group(1);
        Confidence conf = type == ComponentType.OTHER || zone == Zone.UNKNOWN ? Confidence.LOW : Confidence.MEDIUM;
        String desc = d.text.contains(": ") ? d.text.substring(d.text.indexOf(": ") + 2).strip() : "";
        return new Component(d.id, d.name, type, tech, zone, internetFacing, orUnknown(auth), all(DATA_RULES, t), desc,
                d.evidence, conf, null);
    }

    private List<MissingInformation> missing(List<Component> comps) {
        Map<String, String> names = new LinkedHashMap<>();
        comps.forEach(c -> names.put(c.id(), c.name()));
        List<MissingInformation> out = new ArrayList<>();
        for (DataFlow f : flows) {
            String label = names.get(f.source()) + " → " + names.get(f.target());
            if (f.authentication() == AuthMethod.UNKNOWN) {
                out.add(new MissingInformation(label + " akışında kimlik doğrulama nasıl yapılıyor?", f.id()));
            }
            if (f.encrypted() == null) {
                out.add(new MissingInformation(label + " akışı şifreli mi (TLS)?", f.id()));
            }
        }
        for (Component c : comps) {
            if (c.zone() == Zone.UNKNOWN) out.add(new MissingInformation(c.name() + " hangi ağ bölgesinde çalışıyor?", c.id()));
            if (c.type().elementKind() == com.bank.tm.core.model.ElementKind.DATA_STORE && c.dataClasses().isEmpty()) {
                out.add(new MissingInformation(c.name() + " hangi veri türlerini saklıyor?", c.id()));
            }
        }
        return out.size() > 15 ? out.subList(0, 15) : out;
    }

    private static <T> T first(List<Rule<T>> rules, String text) {
        for (Rule<T> r : rules) {
            if (r.pattern().matcher(text).find()) return r.value();
        }
        return null;
    }

    private static <T> List<T> all(List<Rule<T>> rules, String text) {
        List<T> out = new ArrayList<>();
        for (Rule<T> r : rules) {
            if (r.pattern().matcher(text).find() && !out.contains(r.value())) out.add(r.value());
        }
        return out;
    }

    private static AuthMethod orUnknown(AuthMethod a) {
        return a == null ? AuthMethod.UNKNOWN : a;
    }
}
