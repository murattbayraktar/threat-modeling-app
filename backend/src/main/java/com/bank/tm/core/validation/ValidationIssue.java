package com.bank.tm.core.validation;

/**
 * Doğrulama bulgusu. ERROR: öğe/çıktı düzeltildi veya çıkarıldı. WARNING: analistin dikkat etmesi gereken durum.
 * INFO: bilgilendirme.
 */
public record ValidationIssue(Severity severity, String code, String elementId, String message) {

    public enum Severity { ERROR, WARNING, INFO }

    public static ValidationIssue error(String code, String elementId, String message) {
        return new ValidationIssue(Severity.ERROR, code, elementId, message);
    }

    public static ValidationIssue warning(String code, String elementId, String message) {
        return new ValidationIssue(Severity.WARNING, code, elementId, message);
    }

    public static ValidationIssue info(String code, String elementId, String message) {
        return new ValidationIssue(Severity.INFO, code, elementId, message);
    }
}
