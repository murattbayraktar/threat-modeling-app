package com.bank.tm.core.rules;

import com.bank.tm.core.model.Stride;
import com.bank.tm.core.risk.JsonRiskRule;
import com.bank.tm.core.risk.RiskEffect;
import com.bank.tm.core.risk.RiskMethodology;
import com.bank.tm.core.risk.RiskRule;
import com.bank.tm.core.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kural dosyalarını (datasets/rules) yükler ve doğrular. Dosyalar değiştirildiğinde {@link #reload()} ile uygulama
 * yeniden başlatılmadan güncellenir. Hatalı bir dosya yüklenmez; önceki geçerli kurallar kullanılmaya devam eder.
 */
public class RuleRepository {

    private final Path rulesDir;
    private final ConditionParser parser;
    private final ConditionEvaluator evaluator;

    private volatile Snapshot snapshot;

    private record Snapshot(String threatRulesVersion, List<ThreatRule> threatRules, String riskRulesVersion,
                            List<RiskRule> riskRules, RiskMethodology methodology) {
    }

    public RuleRepository(Path rulesDir, OperatorRegistry operators) {
        this.rulesDir = rulesDir;
        this.parser = new ConditionParser(operators);
        this.evaluator = new ConditionEvaluator(operators);
        reload();
    }

    public synchronized void reload() {
        try {
            JsonNode threats = Json.MAPPER.readTree(Files.readString(rulesDir.resolve("threat-rules.json")));
            JsonNode risk = Json.MAPPER.readTree(Files.readString(rulesDir.resolve("risk-rules.json")));
            RiskMethodology methodology = Json.MAPPER.readValue(
                    Files.readString(rulesDir.resolve("risk-methodology.json")), RiskMethodology.class);
            validateMethodology(methodology);
            snapshot = new Snapshot(threats.path("version").asText("?"), parseThreatRules(threats),
                    risk.path("version").asText("?"), parseRiskRules(risk), methodology);
        } catch (IOException e) {
            throw new IllegalStateException("Kural dosyaları okunamadı: " + rulesDir + " – " + e.getMessage(), e);
        }
    }

    public List<ThreatRule> threatRules() { return snapshot.threatRules(); }

    public List<RiskRule> riskRules() { return snapshot.riskRules(); }

    public RiskMethodology methodology() { return snapshot.methodology(); }

    public String threatRulesVersion() { return snapshot.threatRulesVersion(); }

    public String riskRulesVersion() { return snapshot.riskRulesVersion(); }

    public ConditionEvaluator evaluator() { return evaluator; }

    public Path rulesDir() { return rulesDir; }

    private List<ThreatRule> parseThreatRules(JsonNode root) {
        List<ThreatRule> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode r : root.path("rules")) {
            String id = required(r, "id", "threat-rules");
            if (!ids.add(id)) throw new IllegalArgumentException("threat-rules: tekrar eden kural id " + id);
            List<String> tags = new ArrayList<>();
            r.path("tags").forEach(t -> tags.add(t.asText()));
            out.add(new ThreatRule(id, r.path("enabled").asBoolean(true),
                    ThreatRule.Target.valueOf(required(r, "appliesTo", id)),
                    Stride.valueOf(required(r, "stride", id)),
                    r.path("title").asText(id), required(r, "titleTemplate", id),
                    r.path("descriptionTemplate").asText(""),
                    scale(r.path("defaultLikelihood").asInt(3), id), scale(r.path("defaultImpact").asInt(3), id),
                    r.path("ragQuery").asText(""), tags, parser.parse(r.get("when"), id)));
        }
        return List.copyOf(out);
    }

    private List<RiskRule> parseRiskRules(JsonNode root) {
        List<RiskRule> out = new ArrayList<>();
        for (JsonNode r : root.path("rules")) {
            String id = required(r, "id", "risk-rules");
            JsonNode then = r.path("then");
            RiskEffect.Adjustment l = adjustment(then.get("likelihood"), id);
            RiskEffect.Adjustment i = adjustment(then.get("impact"), id);
            if (l == null && i == null) throw new IllegalArgumentException(id + ": then.likelihood veya then.impact gerekli");
            out.add(new JsonRiskRule(id, r.path("order").asInt(100), r.path("enabled").asBoolean(true),
                    r.path("description").asText(id), r.path("rationale").asText(""), parser.parse(r.get("when"), id),
                    l, i, evaluator));
        }
        return List.copyOf(out);
    }

    private static RiskEffect.Adjustment adjustment(JsonNode n, String id) {
        if (n == null || n.isNull()) return null;
        String op = n.path("op").asText();
        if (!List.of("set", "min", "max", "add").contains(op)) {
            throw new IllegalArgumentException(id + ": geçersiz op '" + op + "' (set|min|max|add)");
        }
        return new RiskEffect.Adjustment(op, n.path("value").asInt());
    }

    private static void validateMethodology(RiskMethodology m) {
        if (m.levels() == null || m.levels().isEmpty()) throw new IllegalArgumentException("risk-methodology: levels boş");
        int max = m.scale().max() * m.scale().max();
        for (int s = m.scale().min() * m.scale().min(); s <= max; s++) m.levelFor(s);
        for (Stride s : Stride.values()) {
            if (m.strideDefaults() == null || !m.strideDefaults().containsKey(s.name())) {
                throw new IllegalArgumentException("risk-methodology: strideDefaults eksik " + s);
            }
        }
    }

    private static int scale(int v, String id) {
        if (v < 1 || v > 5) throw new IllegalArgumentException(id + ": olasılık/etki 1-5 aralığında olmalı");
        return v;
    }

    private static String required(JsonNode n, String field, String ctx) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(ctx + ": '" + field + "' alanı zorunlu");
        return v;
    }
}
