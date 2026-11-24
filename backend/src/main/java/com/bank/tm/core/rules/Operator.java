package com.bank.tm.core.rules;

/**
 * Koşul operatörü. Yeni bir operatör eklemek için bu arayüzü uygulayıp {@link OperatorRegistry#register} ile
 * kaydetmek yeterlidir; kural dosyalarında {"op": "<name>"} olarak kullanılabilir.
 */
public interface Operator {

    String name();

    /** @param actual fact değeri (null olabilir) @param expected kuraldaki value (null olabilir) */
    boolean test(Object actual, Object expected);

    /** value alanı zorunlu mu (kural yüklenirken doğrulanır). */
    default boolean requiresValue() { return true; }
}
