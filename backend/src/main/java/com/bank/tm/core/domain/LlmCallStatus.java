package com.bank.tm.core.domain;

public enum LlmCallStatus {
    /** Tarayıcıda çalıştırılmayı bekliyor. */
    PENDING,
    /** Tarayıcı çıktıyı gönderdi, doğrulanmadı. */
    SUBMITTED,
    COMPLETED,
    /** Çıktı şemaya uymadı. */
    INVALID,
    FAILED,
    /** Akış bu adımdan yeniden başlatıldı; kullanılmaz. */
    SUPERSEDED
}
