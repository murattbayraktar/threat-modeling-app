package com.bank.tm.core.pipeline;

import java.util.Map;

public record StageResult(Outcome outcome, String message, Map<String, Object> metrics) {

    public enum Outcome { DONE, WAIT_USER, WAIT_LLM }

    public StageResult {
        metrics = metrics == null ? Map.of() : metrics;
    }

    public static StageResult done(String message, Map<String, Object> metrics) {
        return new StageResult(Outcome.DONE, message, metrics);
    }

    public static StageResult waitUser(String message) {
        return new StageResult(Outcome.WAIT_USER, message, Map.of());
    }

    public static StageResult waitLlm(String message, Map<String, Object> metrics) {
        return new StageResult(Outcome.WAIT_LLM, message, metrics);
    }
}
