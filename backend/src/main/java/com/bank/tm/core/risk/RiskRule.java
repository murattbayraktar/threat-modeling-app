package com.bank.tm.core.risk;

import java.util.Map;
import java.util.Optional;

/**
 * Risk ayarlama kuralı. JSON dosyasındaki kurallar {@link JsonRiskRule} ile, karmaşık mantık gerektiren kurallar
 * doğrudan Java'da bu arayüz uygulanarak yazılır (Spring katmanında bean olarak tanımlanması yeterlidir).
 */
public interface RiskRule {

    String id();

    int order();

    String description();

    /** Kural uygulanıyorsa etkisini döner. */
    Optional<RiskEffect> evaluate(Map<String, Object> facts);
}
