package com.bank.tm.core.domain;

/** Risk motorunun uyguladığı tek bir ayarlama: hangi kural, hangi alan, önce/sonra. */
public record RiskTraceStep(String ruleId, String description, String field, String op, int value, int before,
                            int after, String rationale) {
}
