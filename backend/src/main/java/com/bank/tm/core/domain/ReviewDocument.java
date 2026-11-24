package com.bank.tm.core.domain;

import com.bank.tm.core.parsing.ParsedDocument;

import java.time.Instant;

public record ReviewDocument(
        String id,
        String reviewId,
        String documentKey,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        String storagePath,
        String clientText,
        String clientExtractionMethod,
        ParsedDocument parsed,
        Instant createdAt) {

    public ReviewDocument withParsed(ParsedDocument p) {
        return new ReviewDocument(id, reviewId, documentKey, fileName, contentType, sizeBytes, sha256, storagePath,
                clientText, clientExtractionMethod, p, createdAt);
    }
}
