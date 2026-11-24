package com.bank.tm.core.llm;

public record LlmResponse(String text, String model, long durationMs) {
}
