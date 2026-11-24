package com.bank.tm.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record Component(
        String id,
        String name,
        ComponentType type,
        String technology,
        Zone zone,
        Boolean internetFacing,
        AuthMethod authentication,
        List<DataClass> dataClasses,
        String description,
        Evidence evidence,
        Confidence confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) ElementKind elementKind) {

    public Component {
        dataClasses = dataClasses == null ? List.of() : List.copyOf(dataClasses);
        if (type == null) type = ComponentType.OTHER;
        if (zone == null) zone = Zone.UNKNOWN;
        if (authentication == null) authentication = AuthMethod.UNKNOWN;
        if (confidence == null) confidence = Confidence.MEDIUM;
    }

    public Component withElementKind(ElementKind kind) {
        return new Component(id, name, type, technology, zone, internetFacing, authentication, dataClasses,
                description, evidence, confidence, kind);
    }

    public Component withConfidence(Confidence c) {
        return new Component(id, name, type, technology, zone, internetFacing, authentication, dataClasses,
                description, evidence, c, elementKind);
    }
}
