package com.bank.tm.core.model;

public enum ComponentType {
    USER, EXTERNAL_SYSTEM, THIRD_PARTY_SYSTEM, WEB_APP, MOBILE_APP, API_GATEWAY, API, SERVICE, BATCH_JOB,
    DATABASE, FILE_STORAGE, MESSAGE_QUEUE, CORE_BANKING, IDENTITY_PROVIDER, OTHER;

    /** STRIDE-per-element sınıflandırması. */
    public ElementKind elementKind() {
        return switch (this) {
            case USER, EXTERNAL_SYSTEM, THIRD_PARTY_SYSTEM -> ElementKind.EXTERNAL_ENTITY;
            case DATABASE, FILE_STORAGE, MESSAGE_QUEUE -> ElementKind.DATA_STORE;
            default -> ElementKind.PROCESS;
        };
    }
}
