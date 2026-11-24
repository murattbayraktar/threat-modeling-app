package com.bank.tm.core.risk;

/** Bir risk kuralının olasılık/etki üzerindeki etkisi. op: set | min | max | add. */
public record RiskEffect(Adjustment likelihood, Adjustment impact, String rationale) {

    public record Adjustment(String op, int value) {

        public int apply(int current) {
            return switch (op) {
                case "set" -> value;
                case "min" -> Math.max(current, value);
                case "max" -> Math.min(current, value);
                case "add" -> current + value;
                default -> throw new IllegalArgumentException("Bilinmeyen risk operasyonu: " + op);
            };
        }
    }
}
