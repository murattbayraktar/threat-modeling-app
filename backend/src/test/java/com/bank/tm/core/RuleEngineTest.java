package com.bank.tm.core;

import com.bank.tm.core.model.AuthMethod;
import com.bank.tm.core.model.Component;
import com.bank.tm.core.model.ComponentType;
import com.bank.tm.core.model.Confidence;
import com.bank.tm.core.model.DataClass;
import com.bank.tm.core.model.DataFlow;
import com.bank.tm.core.model.Evidence;
import com.bank.tm.core.model.SystemInfo;
import com.bank.tm.core.model.SystemModel;
import com.bank.tm.core.model.ThreatCandidate;
import com.bank.tm.core.model.Zone;
import com.bank.tm.core.rules.Condition;
import com.bank.tm.core.rules.ConditionEvaluator;
import com.bank.tm.core.rules.ConditionParser;
import com.bank.tm.core.rules.Facts;
import com.bank.tm.core.rules.Operator;
import com.bank.tm.core.rules.OperatorRegistry;
import com.bank.tm.core.rules.RuleRepository;
import com.bank.tm.core.rules.ThreatCandidateEngine;
import com.bank.tm.core.util.Json;
import com.bank.tm.core.validation.ModelValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    static SystemModel bankingModel() {
        Evidence ev = new Evidence("D1", "2", "örnek");
        List<Component> comps = List.of(
                new Component("C1", "Web Application", ComponentType.WEB_APP, null, Zone.INTERNET, true, AuthMethod.JWT,
                        List.of(), "", ev, Confidence.HIGH, null),
                new Component("C2", "Customer API", ComponentType.API, null, Zone.DMZ, true, AuthMethod.JWT,
                        List.of(DataClass.CUSTOMER), "", ev, Confidence.HIGH, null),
                new Component("C3", "Customer Database", ComponentType.DATABASE, "PostgreSQL", Zone.INTERNAL, false,
                        AuthMethod.SERVICE_ACCOUNT, List.of(DataClass.PII, DataClass.FINANCIAL), "", ev, Confidence.HIGH, null));
        List<DataFlow> flows = List.of(
                new DataFlow("F1", "C1", "C2", "", "HTTPS", AuthMethod.JWT, true, List.of(), ev, Confidence.HIGH, null),
                new DataFlow("F2", "C2", "C3", "", "SQL", AuthMethod.UNKNOWN, null, List.of(DataClass.PII), ev, Confidence.HIGH, null));
        SystemModel raw = new SystemModel("1.0", new SystemInfo("Banking Application", ""), comps, flows, List.of(), List.of(), List.of());
        return new ModelValidator().validate(raw, List.of(), true).model();
    }

    @Test
    void operatorsAndConditions() {
        OperatorRegistry ops = new OperatorRegistry();
        ConditionParser parser = new ConditionParser(ops);
        ConditionEvaluator eval = new ConditionEvaluator(ops);
        Condition c = parser.parse(Json.tree("""
                {"all":[{"fact":"a","op":"eq","value":"X"},
                        {"any":[{"fact":"b","op":"containsAny","value":["P","Q"]},{"fact":"c","op":"isTrue"}]},
                        {"not":{"fact":"d","op":"in","value":[1,2]}}]}"""), "test");
        assertTrue(eval.evaluate(c, Map.of("a", "X", "b", List.of("Q"), "d", 3)));
        assertFalse(eval.evaluate(c, Map.of("a", "X", "b", List.of("Z"), "d", 3)));
        assertFalse(eval.evaluate(c, Map.of("a", "X", "b", List.of("P"), "d", 2)));
        // ne: null != true
        Condition ne = parser.parse(Json.tree("{\"fact\":\"enc\",\"op\":\"ne\",\"value\":true}"), "t");
        assertTrue(eval.evaluate(ne, Map.of()));
    }

    @Test
    void unknownOperatorIsRejectedAtLoad() {
        ConditionParser parser = new ConditionParser(new OperatorRegistry());
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(Json.tree("{\"fact\":\"a\",\"op\":\"between\",\"value\":1}"), "kural"));
    }

    @Test
    void customOperatorCanBeRegistered() {
        OperatorRegistry ops = new OperatorRegistry();
        ops.register(new Operator() {
            public String name() { return "startsWith"; }
            public boolean test(Object a, Object e) { return a != null && a.toString().startsWith(e.toString()); }
        });
        Condition c = new ConditionParser(ops).parse(Json.tree("{\"fact\":\"n\",\"op\":\"startsWith\",\"value\":\"Cus\"}"), "t");
        assertTrue(new ConditionEvaluator(ops).evaluate(c, Map.of("n", "Customer API")));
    }

    @Test
    void candidatesFromDatasetRules() {
        OperatorRegistry ops = new OperatorRegistry();
        RuleRepository rules = new RuleRepository(Fixtures.datasets().resolve("rules"), ops);
        SystemModel model = bankingModel();
        assertEquals(Boolean.TRUE, model.flow("F2").orElseThrow().crossesTrustBoundary());
        List<ThreatCandidate> list = new ThreatCandidateEngine(rules, new ConditionEvaluator(ops)).generate(model, null);
        List<String> ids = list.stream().map(ThreatCandidate::id).toList();
        assertTrue(ids.contains("TR-F-T-02:F2"), "SQL enjeksiyonu adayı beklenir: " + ids);
        assertTrue(ids.contains("TR-C-I-01:C3"), "Veritabanı ifşa adayı beklenir");
        assertTrue(ids.contains("TR-F-S-01:F2"), "Kimlik doğrulaması bilinmeyen sınır geçen akış");
        assertTrue(ids.contains("TR-C-I-03:C2"), "BOLA adayı");
        assertFalse(ids.contains("TR-F-S-01:F1"), "JWT ile doğrulanan akış için kimlik adayı beklenmez");
        ThreatCandidate sql = list.stream().filter(c -> c.id().equals("TR-F-T-02:F2")).findFirst().orElseThrow();
        assertEquals("Customer API üzerinden Customer Database veritabanına SQL enjeksiyonu", sql.title());
    }

    @Test
    void factsExposeRelatedDataClasses() {
        SystemModel model = bankingModel();
        Map<String, Object> f = Facts.forFlow(model, model.flow("F2").orElseThrow(), null);
        assertEquals("DATA_FLOW", f.get("element.kind"));
        assertTrue(((List<?>) f.get("element.relatedDataClasses")).contains("FINANCIAL"));
        assertEquals("DATABASE", f.get("target.type"));
    }
}
