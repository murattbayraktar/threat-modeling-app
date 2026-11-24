package com.bank.tm.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Optional;

/**
 * Standart sistem modeli (contracts/system-model.schema.json). Tehdit kuralları ve risk motoru yalnızca bu
 * yapı üzerinde çalışır; ham doküman metnine erişmez.
 */
public record SystemModel(
        String schemaVersion,
        SystemInfo system,
        List<Component> components,
        List<DataFlow> dataFlows,
        List<TrustBoundary> trustBoundaries,
        List<MissingInformation> missingInformation,
        List<String> assumptions) {

    public static final String CURRENT_VERSION = "1.0";

    public SystemModel {
        if (schemaVersion == null) schemaVersion = CURRENT_VERSION;
        if (system == null) system = new SystemInfo("", "");
        components = components == null ? List.of() : List.copyOf(components);
        dataFlows = dataFlows == null ? List.of() : List.copyOf(dataFlows);
        trustBoundaries = trustBoundaries == null ? List.of() : List.copyOf(trustBoundaries);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
    }

    public Optional<Component> component(String id) {
        return components.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Optional<DataFlow> flow(String id) {
        return dataFlows.stream().filter(f -> f.id().equals(id)).findFirst();
    }

    public boolean hasElement(String id) {
        return component(id).isPresent() || flow(id).isPresent();
    }

    /** Bileşen adı veya akış için "Kaynak → Hedef". */
    @JsonIgnore
    public String elementName(String id) {
        Optional<Component> c = component(id);
        if (c.isPresent()) return c.get().name();
        return flow(id).map(f -> componentName(f.source()) + " → " + componentName(f.target())).orElse(id);
    }

    private String componentName(String id) {
        return component(id).map(Component::name).orElse(id);
    }

    public SystemModel withParts(List<Component> c, List<DataFlow> f, List<TrustBoundary> tb,
                                 List<MissingInformation> mi) {
        return new SystemModel(schemaVersion, system, c, f, tb, mi, assumptions);
    }
}
