package com.bank.tm.core.model;

import java.util.Set;

public enum DataClass {
    PUBLIC, INTERNAL, PII, CUSTOMER, FINANCIAL, CARD, CREDENTIAL;

    public static final Set<DataClass> SENSITIVE = Set.of(PII, CUSTOMER, FINANCIAL, CARD, CREDENTIAL);
}
