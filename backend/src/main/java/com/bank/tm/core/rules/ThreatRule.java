package com.bank.tm.core.rules;

import com.bank.tm.core.model.Stride;

import java.util.List;

/** datasets/rules/threat-rules.json içindeki tek kural. */
public record ThreatRule(
        String id,
        boolean enabled,
        Target appliesTo,
        Stride stride,
        String title,
        String titleTemplate,
        String descriptionTemplate,
        int defaultLikelihood,
        int defaultImpact,
        String ragQuery,
        List<String> tags,
        Condition when) {

    public enum Target { COMPONENT, DATA_FLOW }
}
