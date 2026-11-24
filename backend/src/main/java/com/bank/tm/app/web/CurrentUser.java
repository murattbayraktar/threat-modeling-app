package com.bank.tm.app.web;

/**
 * Faz 1'de kullanıcı kimliği istek başlığından (X-User) okunur. Kurumsal SSO entegrasyonunda bu sınıf
 * doğrulanmış kimliği (ör. Spring Security principal) döndürecek şekilde değiştirilir.
 */
final class CurrentUser {

    private CurrentUser() {
    }

    static String of(String header) {
        if (header == null || header.isBlank()) return "analist";
        String h = header.strip();
        return h.length() > 100 ? h.substring(0, 100) : h;
    }
}
