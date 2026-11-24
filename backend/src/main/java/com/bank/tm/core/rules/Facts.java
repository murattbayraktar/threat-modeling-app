package com.bank.tm.core.rules;

import com.bank.tm.core.domain.Review;
import com.bank.tm.core.model.Component;
import com.bank.tm.core.model.DataClass;
import com.bank.tm.core.model.DataFlow;
import com.bank.tm.core.model.Stride;
import com.bank.tm.core.model.SystemModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kuralların okuduğu düz fact haritası. Kurallar yalnızca burada üretilen anahtarları görür; yeni bir bilgiye
 * kural yazmak için önce buraya eklenmelidir. Anahtar listesi docs/kural-motoru.md içinde belgelenmiştir.
 */
public final class Facts {

    private Facts() {
    }

    public static Map<String, Object> forComponent(SystemModel model, Component c, Review review) {
        Map<String, Object> f = base(model, review);
        putComponent(f, "element", c, model);
        f.put("element.isFlow", false);
        return f;
    }

    public static Map<String, Object> forFlow(SystemModel model, DataFlow flow, Review review) {
        Map<String, Object> f = base(model, review);
        f.put("element.id", flow.id());
        f.put("element.name", model.elementName(flow.id()));
        f.put("element.kind", "DATA_FLOW");
        f.put("element.isFlow", true);
        f.put("element.protocol", flow.protocol());
        f.put("element.authentication", flow.authentication().name());
        f.put("element.encrypted", flow.encrypted());
        f.put("element.crossesTrustBoundary", flow.crossesTrustBoundary());
        f.put("element.dataClasses", names(flow.dataClasses()));
        Set<String> related = new LinkedHashSet<>(names(flow.dataClasses()));
        model.component(flow.source()).ifPresent(c -> {
            putComponent(f, "source", c, model);
            related.addAll(names(c.dataClasses()));
        });
        model.component(flow.target()).ifPresent(c -> {
            putComponent(f, "target", c, model);
            related.addAll(names(c.dataClasses()));
        });
        f.put("element.relatedDataClasses", new ArrayList<>(related));
        return f;
    }

    /** Risk motoru için: tehdidin hedef öğesi + tehdit bilgileri. */
    public static Map<String, Object> forThreat(SystemModel model, String targetId, Stride stride, String ruleId,
                                                boolean aiAdditional, Review review) {
        Map<String, Object> f = model.component(targetId).map(c -> forComponent(model, c, review))
                .or(() -> model.flow(targetId).map(fl -> forFlow(model, fl, review)))
                .orElseGet(() -> base(model, review));
        f.put("threat.stride", stride == null ? null : stride.name());
        f.put("threat.ruleId", ruleId);
        f.put("threat.aiAdditional", aiAdditional);
        return f;
    }

    private static Map<String, Object> base(SystemModel model, Review review) {
        Map<String, Object> f = new LinkedHashMap<>();
        Set<String> all = new LinkedHashSet<>();
        model.components().forEach(c -> all.addAll(names(c.dataClasses())));
        model.dataFlows().forEach(fl -> all.addAll(names(fl.dataClasses())));
        f.put("system.dataClasses", new ArrayList<>(all));
        f.put("system.componentCount", model.components().size());
        if (review != null) {
            f.put("review.projectType", review.projectType());
            f.put("review.dataClassification", review.dataClassification());
            review.intakeAnswers().forEach((k, v) -> f.put("review.answers." + k, v));
        }
        return f;
    }

    private static void putComponent(Map<String, Object> f, String prefix, Component c, SystemModel model) {
        f.put(prefix + ".id", c.id());
        f.put(prefix + ".name", c.name());
        f.put(prefix + ".type", c.type().name());
        f.put(prefix + ".kind", c.type().elementKind().name());
        f.put(prefix + ".zone", c.zone().name());
        f.put(prefix + ".internetFacing", c.internetFacing());
        f.put(prefix + ".authentication", c.authentication().name());
        f.put(prefix + ".technology", c.technology());
        f.put(prefix + ".dataClasses", names(c.dataClasses()));
        if (prefix.equals("element")) {
            Set<String> related = new LinkedHashSet<>(names(c.dataClasses()));
            model.dataFlows().stream().filter(fl -> fl.source().equals(c.id()) || fl.target().equals(c.id()))
                    .forEach(fl -> related.addAll(names(fl.dataClasses())));
            f.put("element.relatedDataClasses", new ArrayList<>(related));
        }
    }

    private static List<String> names(List<DataClass> classes) {
        return classes.stream().map(Enum::name).toList();
    }
}
