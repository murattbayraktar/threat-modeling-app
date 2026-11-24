package com.bank.tm.core.model;

import java.util.List;

/** LLM structured output: contracts/llm/threat-generation.output.schema.json */
public record ThreatGenerationOutput(List<LlmThreat> threats) {

    public ThreatGenerationOutput {
        threats = threats == null ? List.of() : threats;
    }

    public record LlmThreat(
            String candidateId,
            boolean applicable,
            String notApplicableReason,
            String title,
            String description,
            String attackScenario,
            Stride strideCategory,
            String targetId,
            int suggestedLikelihood,
            int suggestedImpact,
            String riskRationale,
            List<LlmMitigation> mitigations,
            List<String> sourceIds,
            Confidence confidence,
            List<String> assumptions) {

        public LlmThreat {
            mitigations = mitigations == null ? List.of() : mitigations;
            sourceIds = sourceIds == null ? List.of() : sourceIds;
            assumptions = assumptions == null ? List.of() : assumptions;
        }
    }

    public record LlmMitigation(String title, String description, List<String> sourceIds) {
        public LlmMitigation {
            sourceIds = sourceIds == null ? List.of() : sourceIds;
        }
    }
}
