package com.bank.tm.core.model;

import java.util.List;

/** LLM structured output: contracts/llm/model-extraction.output.schema.json */
public record ModelExtractionOutput(
        SystemInfo system,
        List<Component> components,
        List<DataFlow> dataFlows,
        List<MissingInformation> missingInformation,
        List<String> assumptions) {

    public ModelExtractionOutput {
        components = components == null ? List.of() : components;
        dataFlows = dataFlows == null ? List.of() : dataFlows;
        missingInformation = missingInformation == null ? List.of() : missingInformation;
        assumptions = assumptions == null ? List.of() : assumptions;
    }

    public SystemModel toSystemModel() {
        return new SystemModel(SystemModel.CURRENT_VERSION, system, components, dataFlows, List.of(),
                missingInformation, assumptions);
    }
}
