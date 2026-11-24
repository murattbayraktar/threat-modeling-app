package com.bank.tm.core.domain;

import java.time.Instant;

/** Uzman kararlarının değiştirilemez kaydı (kim, ne zaman, önce/sonra). */
public record ThreatEvent(String id, String threatId, String reviewId, String actor, String action,
                          String beforeJson, String afterJson, String note, Instant createdAt) {
}
