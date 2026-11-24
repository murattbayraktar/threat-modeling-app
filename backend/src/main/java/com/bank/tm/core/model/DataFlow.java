package com.bank.tm.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record DataFlow(
        String id,
        String source,
        String target,
        String description,
        String protocol,
        AuthMethod authentication,
        Boolean encrypted,
        List<DataClass> dataClasses,
        Evidence evidence,
        Confidence confidence,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean crossesTrustBoundary) {

    public DataFlow {
        dataClasses = dataClasses == null ? List.of() : List.copyOf(dataClasses);
        if (authentication == null) authentication = AuthMethod.UNKNOWN;
        if (confidence == null) confidence = Confidence.MEDIUM;
    }

    public DataFlow withCrossesTrustBoundary(Boolean value) {
        return new DataFlow(id, source, target, description, protocol, authentication, encrypted, dataClasses,
                evidence, confidence, value);
    }

    public DataFlow withConfidence(Confidence c) {
        return new DataFlow(id, source, target, description, protocol, authentication, encrypted, dataClasses,
                evidence, c, crossesTrustBoundary);
    }
}
