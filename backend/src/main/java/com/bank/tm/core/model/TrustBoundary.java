package com.bank.tm.core.model;

import java.util.List;

public record TrustBoundary(String id, Zone zone, List<String> componentIds) {
    public TrustBoundary {
        componentIds = componentIds == null ? List.of() : List.copyOf(componentIds);
    }
}
