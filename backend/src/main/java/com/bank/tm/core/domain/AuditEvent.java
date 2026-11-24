package com.bank.tm.core.domain;

import java.time.Instant;

public record AuditEvent(String id, String reviewId, String actor, String type, String message, Instant createdAt) {
}
